package org.cloudbus.cloudsim.cosched.workload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes jobs in the job CSV format ({@code docs/formats.md}), so a generated workload is kept next to
 * the run's results and can be replayed exactly with {@code workload.csv}.
 */
public final class JobCsvWriter {

    public static final String HEADER = "job_id,arrival_s,gpus,model,iterations,iter_time_s,duration_s,team,priority";

    private JobCsvWriter() {
    }

    public static void write(Path file, List<JobRecord> jobs) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write(HEADER);
                w.write('\n');
                for (JobRecord j : jobs) {
                    w.write(j.jobId() + "," + num(j.arrivalS()) + "," + j.gpus() + "," + str(j.model()) + ","
                            + num(j.iterations()) + "," + num(j.iterTimeS()) + "," + num(j.durationS()) + ","
                            + str(j.team()) + "," + str(j.priority()) + "\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) throw new IllegalArgumentException("value with a comma or quote: " + s);
        return s;
    }

    /** Shortest exact decimal form; Double.toString round-trips, so the CSV reproduces the run. */
    private static String num(Double d) {
        if (d == null) return "";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
        return Double.toString(d);
    }
}
