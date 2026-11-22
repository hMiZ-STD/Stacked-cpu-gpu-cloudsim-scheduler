package org.cloudbus.cloudsim.cosched.workload;

/**
 * One row of the job CSV (format in {@code docs/formats.md}). Work is given either as
 * {@code iterations} (optionally with {@code iterTimeS}, Synergy-style) or as {@code durationS},
 * the job's run time at the fixed share (trace-style). Optional fields are null when absent.
 *
 * @param line 1-based line in the CSV, for error messages
 */
public record JobRecord(int jobId, double arrivalS, int gpus,
                        Double iterations, Double iterTimeS, Double durationS,
                        String model, String team, String priority, int line) {

    public JobRecord {
        String at = "job CSV line " + line + " (job " + jobId + "): ";
        if (arrivalS < 0) throw new IllegalArgumentException(at + "arrival_s must be >= 0");
        if (gpus <= 0) throw new IllegalArgumentException(at + "gpus must be > 0");
        boolean hasIter = iterations != null, hasDur = durationS != null;
        if (hasIter == hasDur) throw new IllegalArgumentException(at + "give exactly one of iterations or duration_s");
        if (hasIter && !(iterations > 0)) throw new IllegalArgumentException(at + "iterations must be > 0");
        if (iterTimeS != null && !(iterTimeS > 0)) throw new IllegalArgumentException(at + "iter_time_s must be > 0");
        if (hasDur && !(durationS > 0)) throw new IllegalArgumentException(at + "duration_s must be > 0");
        if (hasDur && iterTimeS != null) throw new IllegalArgumentException(at + "iter_time_s goes with iterations, not duration_s");
        model = blankToNull(model);
        team = blankToNull(team);
        priority = blankToNull(priority);
    }

    public JobRecord withModel(String m) {
        return new JobRecord(jobId, arrivalS, gpus, iterations, iterTimeS, durationS, m, team, priority, line);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
