package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;

import java.util.List;

/** Decides which job gets resources first in a round. */
public interface OrderingPolicy {

    /**
     * {@code jobs} sorted so the job to serve first comes first. Must not modify the input. The throughput
     * model is there for orderings that estimate remaining time at the job's ideal speed (SRTF, FTF).
     */
    List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel throughput);

    /**
     * Synergy's LAS+tune quirk ({@code schedulers/las_synergy_new.py}): choose the jobs to run this round by
     * walking the order and keeping each job whose GPUs still fit the cluster's free GPU count, then place the
     * chosen jobs <em>largest GPU demand first</em> (stable, so equal demands keep the policy's order). Not in
     * the paper; no other Synergy ordering does this. Off unless the candidate asks for it.
     */
    default boolean largestGpuDemandFirst() {
        return false;
    }
}
