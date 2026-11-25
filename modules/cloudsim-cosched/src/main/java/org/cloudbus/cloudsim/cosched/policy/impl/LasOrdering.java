package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.Starvation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Least attained service (Tiresias-style). Synergy's key ({@code schedulers/las_synergy_new.py}):
 * (starved first, attained service in GPU-seconds, arrival).
 */
public final class LasOrdering implements OrderingPolicy {

    private final Starvation starvation = new Starvation();
    private final boolean largestFirst;

    public LasOrdering() {
        this(false);
    }

    /** @param largestFirst Synergy's LAS+tune quirk, see {@link OrderingPolicy#largestGpuDemandFirst()} */
    public LasOrdering(boolean largestFirst) {
        this.largestFirst = largestFirst;
    }

    @Override
    public boolean largestGpuDemandFirst() {
        return largestFirst;
    }

    @Override
    public List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel tm) {
        List<JobInfo> l = new ArrayList<>(jobs);
        l.sort(Comparator.<JobInfo>comparingInt(j -> starvation.isBoosted(j) ? 0 : 1)
                .thenComparingDouble(JobInfo::attainedServiceGpuSeconds)
                .thenComparingDouble(JobInfo::arrivalTime)
                .thenComparingInt(JobInfo::id));
        starvation.update(jobs, state.now());
        return l;
    }
}
