package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.RemainingEstimate;
import org.cloudbus.cloudsim.cosched.policy.Starvation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shortest remaining time first. Synergy's key ({@code schedulers/srtf_synergy_new.py}):
 * (starved first, remaining duration, arrival).
 */
public final class SrtfOrdering implements OrderingPolicy {

    private final RemainingEstimate estimate;
    private final Starvation starvation = new Starvation();

    public SrtfOrdering(RemainingEstimate estimate) {
        this.estimate = estimate;
    }

    @Override
    public List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel tm) {
        Map<Integer, Double> key = new HashMap<>();
        for (JobInfo j : jobs) key.put(j.id(), estimate.remainingS(j, state, tm));
        List<JobInfo> l = new ArrayList<>(jobs);
        l.sort(Comparator.<JobInfo>comparingInt(j -> starvation.isBoosted(j) ? 0 : 1)
                .thenComparingDouble(j -> key.get(j.id()))
                .thenComparingDouble(JobInfo::arrivalTime)
                .thenComparingInt(JobInfo::id));
        starvation.update(jobs, state.now());
        return l;
    }
}
