package org.cloudbus.cloudsim.cosched.metrics;

import org.cloudbus.cloudsim.cosched.broker.Job;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.cloudbus.cloudsim.cosched.core.Allocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row per finished job: arrival, first start, finish, job completion time (finish - arrival,
 * queueing included, as Synergy defines it), queueing delay, and its last allocation. The
 * {@code job_id, jct_s} columns are what {@code tools/compare_runs.py} reads.
 */
public final class JobMetrics {

    public static final String HEADER = "job_id,model,gpus,arrival_s,start_s,finish_s,jct_s,queue_s,starts,cpus_per_gpu,mem_gb_per_gpu";

    public record Row(int jobId, String model, int gpus, double arrivalS, double startS, double finishS,
                      int starts, double cpusPerGpu, double memGbPerGpu) {
        public double jctS() { return finishS - arrivalS; }
        public double queueS() { return startS - arrivalS; }
    }

    private final List<Row> rows = new ArrayList<>();

    /** Called once per job when it ends. */
    public void record(Job j) {
        Allocation a = j.lastAllocation();
        rows.add(new Row(j.id(), j.model(), j.gpus(), j.arrivalTime(), j.firstStartTime(), j.finishTime(), j.starts(),
                a.total().cpusPerGpu(), a.total().memGb() / a.gpus()));
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    /** Writes the rows sorted by job id. */
    public void writeCsv(Path file) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(Row::jobId));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write(HEADER);
                w.write('\n');
                for (Row r : sorted)
                    w.write(String.format(Locale.ROOT, "%d,%s,%d,%s,%s,%s,%s,%s,%d,%s,%s%n", r.jobId(), r.model(), r.gpus(),
                            num(r.arrivalS()), num(r.startS()), num(r.finishS()), num(r.jctS()), num(r.queueS()), r.starts(),
                            num(r.cpusPerGpu()), num(r.memGbPerGpu())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Mean, p99, p99.9 JCT (hours, Synergy's summary) and mean queueing delay (s), over all jobs. */
    public Map<String, Object> summary() {
        return summary(null);
    }

    /**
     * Same, with JCT and queueing statistics over the jobs {@code measure} covers (all when null), as Synergy
     * measures jobs 4000-4999. {@code jobs} counts every finished job and the makespan covers the whole run.
     */
    public Map<String, Object> summary(ExperimentConfig.Measure measure) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("jobs", rows.size());
        List<Row> m = measure == null ? rows : rows.stream().filter(r -> measure.covers(r.jobId())).toList();
        if (measure != null) s.put("jobs_measured", m.size());
        if (rows.isEmpty()) return s;
        s.put("makespan_s", rows.stream().mapToDouble(Row::finishS).max().orElse(0));
        if (m.isEmpty()) return s;
        double[] jct = m.stream().mapToDouble(Row::jctS).sorted().toArray();
        s.put("mean_jct_h", Arrays.stream(jct).average().orElse(0) / 3600);
        s.put("p99_jct_h", percentile(jct, 99) / 3600);
        s.put("p99.9_jct_h", percentile(jct, 99.9) / 3600);
        s.put("mean_queue_s", m.stream().mapToDouble(Row::queueS).average().orElse(0));
        return s;
    }

    /** Percentile with linear interpolation between ranks (NumPy's default), on sorted values. */
    static double percentile(double[] sorted, double p) {
        double rank = p / 100 * (sorted.length - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
    }

    /** Rounded to the microsecond (far below CloudSim's 0.02 s resolution), shortest decimal form. */
    private static String num(double d) {
        double r = Math.round(d * 1e6) / 1e6;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return Long.toString((long) r);
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }
}
