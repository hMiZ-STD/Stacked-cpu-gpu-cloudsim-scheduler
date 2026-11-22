package org.cloudbus.cloudsim.cosched.workload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Jobs from a job CSV, in arrival order (ties broken by job id), each labelled with its model.
 * Labels are assigned in that order, which is the order Synergy labels arriving jobs.
 */
public final class TraceWorkload {

    private final List<JobRecord> jobs;

    public TraceWorkload(List<JobRecord> records, ModelLabeler labeler) {
        List<JobRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingDouble(JobRecord::arrivalS).thenComparingInt(JobRecord::jobId));
        List<JobRecord> out = new ArrayList<>(sorted.size());
        for (JobRecord r : sorted) out.add(r.withModel(labeler.label(r)));
        this.jobs = List.copyOf(out);
    }

    public static TraceWorkload fromCsv(Path csv, ModelLabeler labeler) {
        return new TraceWorkload(JobCsvReader.read(csv), labeler);
    }

    /** Labelled jobs in arrival order. */
    public List<JobRecord> jobs() {
        return jobs;
    }
}
