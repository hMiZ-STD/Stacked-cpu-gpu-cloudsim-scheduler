package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;

import java.util.List;

/**
 * How fast a job trains with a given allocation. Policies ask this model; they never read
 * profile files (architecture: "policies ask the model, never the profile files").
 *
 * <p>Speedup is relative to the job's speed at the GPU-proportional ("fixed m:n") share of the
 * server group it runs on, so 1.0 means "as fast as the fixed share".
 */
public interface ThroughputModel {

    /** Speedup over the fixed share when holding {@code total} on servers of {@code group}. */
    double speedup(JobInfo job, ResourceVector total, ServerGroup group);

    /**
     * What the job would ideally hold on {@code group}: its GPUs, and per-GPU cores and memory from
     * its profile, capped at one server's size.
     */
    ResourceVector idealDemand(JobInfo job, ServerGroup group);

    /**
     * Fraction of time the job's GPUs compute instead of waiting for input:
     * {@code speedup(total) / speedup(idealDemand)}, at most 1.
     */
    default double gpuBusyFraction(JobInfo job, ResourceVector total, ServerGroup group) {
        double best = speedup(job, idealDemand(job, group), group);
        return Math.min(1, speedup(job, total, group) / best);
    }

    /** Seconds per iteration at the fixed share (from the job if it states one, else its profile). */
    double iterationTime(JobInfo job, ServerGroup group);

    /** Warnings raised so far (for example allocations beyond measured CPU points), for the run log. */
    List<String> warnings();
}
