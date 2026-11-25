package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Throughput from measured profile tables.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Mode#TABLE}: speedup = T(c, m) / T(fixed share), read off the table at the job's cores c
 *       and memory m per GPU and divided by the table at the fixed share of the server group. CPU points
 *       are linearly interpolated; the memory column is the largest measured value at or below m.
 *       Below the first measured point the first value is used; beyond the last point throughput stays
 *       at the last value and a warning is recorded once per model. Nothing is extrapolated.</li>
 *   <li>{@link Mode#SYNERGY_TWO_POINT}: exactly what Synergy's simulator does. A job holding at least
 *       its ideal cores and memory per GPU runs at the profile's {@code ideal_speedup}, anything else
 *       at 1.0. Synergy's Greedy and Tune only ever give these two points.</li>
 * </ul>
 *
 * <p>Normalising to the server's own fixed share matters: Synergy's {@code models-4/5/6} keep the same
 * table (normalised at 3 cores per GPU) but state {@code ideal_speedup} relative to 4, 5 and 6 cores
 * per GPU (32, 40, 48-CPU servers). With this normalisation the table reproduces those speedups.
 */
public final class ProfileTableModel implements ThroughputModel {

    public enum Mode {
        TABLE, SYNERGY_TWO_POINT;

        public static Mode parse(String s) {
            return switch (s) {
                case "table" -> TABLE;
                case "synergy_two_point" -> SYNERGY_TWO_POINT;
                default -> throw new IllegalArgumentException("throughput mode must be 'table' or 'synergy_two_point', got '" + s + "'");
            };
        }
    }

    private static final double EPS = 1e-9;

    private final ProfileRepository profiles;
    private final Mode mode;
    private final Set<String> warned = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();

    public ProfileTableModel(ProfileRepository profiles, Mode mode) {
        this.profiles = profiles;
        this.mode = mode;
        if (mode == Mode.SYNERGY_TWO_POINT)
            for (Profile p : profiles.all()) {
                // Synergy's 4th resource, storage speed, equals memory in every profile it uses; we leave it out.
                if (p.idealStorageSpeedPerGpu() != null && Math.abs(p.idealStorageSpeedPerGpu() - p.idealMemGbPerGpu()) > EPS)
                    throw new IllegalArgumentException(p + ": storage-speed demand differs from memory demand; "
                            + "Synergy's storage dimension would then matter, which this model leaves out");
            }
    }

    /** A job's ideal demand on a server group and the speedup it gets there (Synergy's rule). */
    public record IdealPoint(ResourceVector demand, double speedup) {
    }

    /**
     * Synergy's ideal demand ({@code jobs/workload.py, add_synergy_profile}): GPUs x the profile's ideal
     * cores and memory per GPU. If that exceeds one server, it is capped at the server and the speedup is
     * re-interpolated on the straight line between the fixed share (speedup 1) and the ideal point, in
     * cores. A job with more GPUs than a server gets the fixed share and speedup 1 instead.
     */
    public IdealPoint idealPoint(JobInfo job, ServerGroup group) {
        Profile p = profile(job, group);
        int g = job.gpus();
        double cpus = g * p.idealCpusPerGpu(), mem = g * p.idealMemGbPerGpu(), s = p.idealSpeedup();
        boolean reinterpolate = false;
        if (cpus > group.cpus()) {
            cpus = group.cpus();
            reinterpolate = true;
            if (g > group.gpus()) {
                cpus = g * group.cpus() / group.gpus();
                s = 1;
                reinterpolate = false;
            }
        }
        if (mem > group.memGb()) {
            mem = group.memGb();
            reinterpolate = true;
            if (g > group.gpus()) {
                mem = g * group.memGb() / group.gpus();
                s = 1;
                reinterpolate = false;
            }
        }
        if (reinterpolate) {
            double fairCpus = g * group.cpus() / group.gpus(), idealCpus = g * p.idealCpusPerGpu();
            if (Math.abs(idealCpus - fairCpus) > EPS)   // Synergy raises an error when they are equal; keep the speedup
                s = 1 + (s - 1) / (idealCpus - fairCpus) * (cpus - fairCpus);
        }
        return new IdealPoint(new ResourceVector(g, cpus, mem), s);
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public double speedup(JobInfo job, ResourceVector total, ServerGroup group) {
        if (total.gpus() != job.gpus()) throw new IllegalArgumentException("job " + job.id() + " holds " + total.gpus() + " GPUs, asked for " + job.gpus());
        Profile p = profile(job, group);
        double c = total.cpus() / total.gpus(), m = total.memGb() / total.gpus();
        if (mode == Mode.SYNERGY_TWO_POINT) {
            IdealPoint ip = idealPoint(job, group);
            boolean atIdeal = total.cpus() >= ip.demand().cpus() - EPS && total.memGb() >= ip.demand().memGb() - EPS;
            return atIdeal ? ip.speedup() : 1.0;
        }
        ResourceVector fair = group.proportionalShare(1);
        return lookup(p, c, m, true) / lookup(p, fair.cpus(), fair.memGb(), false);
    }

    @Override
    public ResourceVector idealDemand(JobInfo job, ServerGroup group) {
        return idealPoint(job, group).demand();
    }

    @Override
    public double iterationTime(JobInfo job, ServerGroup group) {
        return profile(job, group).iterTimeS();
    }

    @Override
    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** Table value at (c, m) per GPU. */
    double lookup(Profile p, double c, double m, boolean warn) {
        double[] cpus = p.cpus(), mem = p.memGbPerGpu();
        int last = p.measuredRows() - 1;
        int col = 0;
        for (int j = 0; j < mem.length; j++) if (mem[j] <= m + EPS) col = j;
        if (c >= cpus[last] - EPS) {
            if (warn && c > cpus[last] + EPS) warnOnce(p, "allocation of " + c + " cores per GPU is beyond the largest measured point ("
                    + p.maxMeasuredCpus() + "); throughput held at the last measured value");
            return p.throughput()[last][col];
        }
        if (c <= cpus[0] + EPS) return p.throughput()[0][col];
        int i = 0;
        while (cpus[i + 1] < c) i++;
        double f = (c - cpus[i]) / (cpus[i + 1] - cpus[i]);
        return p.throughput()[i][col] + f * (p.throughput()[i + 1][col] - p.throughput()[i][col]);
    }

    private Profile profile(JobInfo job, ServerGroup group) {
        if (job.model() == null) throw new IllegalStateException("job " + job.id() + " has no model label");
        return profiles.profileFor(job.model(), group.gpuType(), job.gpus());
    }

    private void warnOnce(Profile p, String msg) {
        if (warned.add(p.model() + "/" + p.gpuType())) warnings.add(p + ": " + msg);
    }
}
