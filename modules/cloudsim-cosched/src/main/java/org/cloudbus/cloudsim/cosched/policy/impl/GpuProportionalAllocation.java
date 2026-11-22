package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;

/**
 * The fixed CPU:GPU ratio most schedulers use ("m:n"): per GPU, the server's cores and memory divided
 * by its GPUs. Synergy's "+fair" baseline.
 */
public final class GpuProportionalAllocation implements AllocationPolicy {

    @Override
    public ResourceVector demand(JobInfo job, ServerGroup group, ThroughputModel throughput) {
        return group.proportionalShare(job.gpus());
    }
}
