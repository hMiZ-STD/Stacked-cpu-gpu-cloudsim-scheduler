package org.cloudbus.cloudsim.cosched.metrics;

import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.PowerSettings;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.gpu.api.GpuLayer;
import org.cloudbus.cloudsim.gpu.api.ServerStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cluster utilisation and energy over a run (the architecture's EnergyListener, plus the utilisation part
 * of the MetricListener). The broker calls {@link #sample} after every change to what runs where; between
 * those moments allocations, GPU busy fractions and therefore power are constant, so the integrals below
 * are exact, not sampled approximations.
 *
 * <p>Energy follows the architecture document:
 * {@code E_IT = sum over servers of integral (P_cpu(u_cpu) + sum over GPUs P_gpu(u_gpu) + P_base) dt},
 * {@code E_facility = E_IT * PUE}, {@code CO2 = E_facility * grid factor}. Idle servers stay on.
 *
 * <p>GPU energy not spent computing. With a linear GPU model, a GPU busy a fraction u of the time draws
 * idle power while waiting and maximum power while computing, so it spends {@code (1 - u) * P_idle} on
 * waiting. That waiting energy is split into <em>idle</em> (GPU not allocated to any job) and
 * <em>stalled</em> (allocated, but its job is starved of CPU or memory and the GPU waits for data).
 * The busy fraction is the throughput model's (speedup / ideal speedup), an assumption.
 *
 * <p>Optionally writes a {@code timeline.csv}: one row per change in cluster state.
 */
public final class ClusterMeter {

    private final GpuLayer layer;
    private final int servers;
    private final int totalGpus;
    private final double totalCpus;
    private final double[] gpuIdleW;     // per server, NaN when energy is not modelled
    private final int[] gpusPerServer;
    private final boolean energy;
    private final BufferedWriter timeline;

    private double lastT;
    // values holding since lastT
    private double usedGpus, busyGpus, usedCpus, powerW, cpuW, gpuW, idleGpuW, stalledGpuW;
    // integrals over [0, lastT]
    private double iUsedGpus, iBusyGpus, iUsedCpus, iPower, iCpu, iGpu, iIdleGpu, iStalledGpu;
    private double endT = Double.NaN;
    private String lastRow = "";

    /**
     * @param timelineCsv where to write the timeline, or null for none
     */
    public ClusterMeter(GpuLayer layer, ClusterSpec spec, Path timelineCsv) {
        this.layer = layer;
        List<Server> list = spec.servers();
        this.servers = list.size();
        if (layer.serverCount() != servers)
            throw new IllegalArgumentException("GPU layer has " + layer.serverCount() + " servers, cluster spec " + servers);
        this.totalGpus = spec.totalCapacity().gpus();
        this.totalCpus = spec.totalCapacity().cpus();
        this.gpuIdleW = new double[servers];
        this.gpusPerServer = new int[servers];
        boolean all = true;
        for (Server s : list) {
            PowerSettings p = s.group().power();
            gpuIdleW[s.index()] = p == null ? Double.NaN : p.gpuIdleW();
            gpusPerServer[s.index()] = s.group().gpus();
            all &= p != null;
        }
        this.energy = all;
        try {
            if (timelineCsv != null) {
                Files.createDirectories(timelineCsv.toAbsolutePath().getParent());
                timeline = Files.newBufferedWriter(timelineCsv);
                timeline.write("time_s,used_gpus,busy_gpus,used_cpus" + (energy ? ",it_power_w" : "") + "\n");
            } else timeline = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        read();
        writeRow(0);
    }

    /** True if every server has power settings, so energy is measured. */
    public boolean measuresEnergy() {
        return energy;
    }

    /** The cluster changed at time t (it held its previous state since the last sample). */
    public void sample(double t) {
        if (!Double.isNaN(endT)) throw new IllegalStateException("meter already closed");
        if (t < lastT - 1e-9) throw new IllegalStateException("time went backwards: " + t + " < " + lastT);
        advance(t);
        read();
        writeRow(t);
    }

    /** Ends the measured window at {@code end} (the makespan); the last state holds until then. */
    public void close(double end) {
        if (!Double.isNaN(endT)) return;
        advance(Math.max(end, lastT));
        endT = lastT;
        try {
            if (timeline != null) timeline.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void advance(double t) {
        double dt = t - lastT;
        if (dt <= 0) return;
        iUsedGpus += usedGpus * dt;
        iBusyGpus += busyGpus * dt;
        iUsedCpus += usedCpus * dt;
        if (energy) {
            iPower += powerW * dt;
            iCpu += cpuW * dt;
            iGpu += gpuW * dt;
            iIdleGpu += idleGpuW * dt;
            iStalledGpu += stalledGpuW * dt;
        }
        lastT = t;
    }

    private void read() {
        usedGpus = busyGpus = usedCpus = powerW = cpuW = gpuW = idleGpuW = stalledGpuW = 0;
        for (int i = 0; i < servers; i++) {
            ServerStatus s = layer.status(i);
            int g = gpusPerServer[i];
            double busy = s.gpuUtil() * g;
            usedGpus += s.usedGpus();
            busyGpus += busy;
            usedCpus += s.usedCpus();
            if (energy) {
                powerW += s.powerW();
                cpuW += s.cpuPowerW();
                gpuW += s.gpuPowerW();
                idleGpuW += gpuIdleW[i] * (g - s.usedGpus());
                stalledGpuW += gpuIdleW[i] * Math.max(0, s.usedGpus() - busy);
            }
        }
    }

    private void writeRow(double t) {
        if (timeline == null) return;
        String row = String.format(Locale.ROOT, "%d,%.6f,%.6f", (long) usedGpus, busyGpus, usedCpus)
                + (energy ? String.format(Locale.ROOT, ",%.3f", powerW) : "");
        if (row.equals(lastRow)) return;
        lastRow = row;
        try {
            timeline.write(String.format(Locale.ROOT, "%.6f,", t) + row + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private double window() {
        if (Double.isNaN(endT)) throw new IllegalStateException("close the meter first");
        return endT;
    }

    /** Time-averaged utilisation over [0, makespan]: GPUs allocated, GPUs busy, cores allocated (fractions). */
    public Map<String, Object> utilisation() {
        Map<String, Object> m = new LinkedHashMap<>();
        double w = window();
        if (w <= 0) return m;
        m.put("gpu_alloc_util", iUsedGpus / (w * totalGpus));
        m.put("gpu_busy_util", iBusyGpus / (w * totalGpus));
        m.put("cpu_alloc_util", iUsedCpus / (w * totalCpus));
        return m;
    }

    /** IT energy in kWh over [0, makespan] (NaN when energy is not modelled). */
    public double itEnergyKwh() {
        window();
        return energy ? iPower / 3.6e6 : Double.NaN;
    }

    /**
     * Energy metrics, or an empty map when energy is not modelled.
     *
     * @param jobs    finished jobs, for energy per job
     * @param samples training samples processed in the run (NaN if unknown), for energy per sample
     */
    public Map<String, Object> energy(ExperimentConfig.Energy settings, int jobs, double samples) {
        Map<String, Object> m = new LinkedHashMap<>();
        window();
        if (!energy || settings == null) return m;
        double it = iPower / 3.6e6;                       // J -> kWh
        double facility = it * settings.pue();
        m.put("it_energy_kwh", it);
        m.put("facility_energy_kwh", facility);
        m.put("co2_kg", facility * settings.gridGCo2PerKwh() / 1000);
        m.put("mean_it_power_kw", endT > 0 ? iPower / endT / 1000 : 0.0);
        m.put("kwh_per_job", jobs > 0 ? facility / jobs : Double.NaN);
        if (!Double.isNaN(samples) && samples > 0) m.put("j_per_sample", facility * 3.6e6 / samples);
        m.put("cpu_energy_share", iPower > 0 ? iCpu / iPower : 0.0);
        m.put("gpu_energy_share", iPower > 0 ? iGpu / iPower : 0.0);
        m.put("idle_gpu_energy_share", iPower > 0 ? iIdleGpu / iPower : 0.0);
        m.put("stalled_gpu_energy_share", iPower > 0 ? iStalledGpu / iPower : 0.0);
        return m;
    }
}
