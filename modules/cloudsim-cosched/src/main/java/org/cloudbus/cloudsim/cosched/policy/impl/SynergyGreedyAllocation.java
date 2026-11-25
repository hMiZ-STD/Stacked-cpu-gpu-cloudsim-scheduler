package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;

/**
 * Synergy-Greedy (paper §3.3): every job asks for its ideal cores and memory (capped at one server, as
 * Synergy does); a job that does not fit is skipped for this round. This is Synergy's scheduler with no
 * suffix, e.g. plain {@code FIFO}.
 */
public final class SynergyGreedyAllocation implements AllocationPolicy {

    @Override
    public ResourceVector demand(JobInfo job, ServerGroup group, ThroughputModel throughput) {
        return throughput.idealDemand(job, group);
    }
}
