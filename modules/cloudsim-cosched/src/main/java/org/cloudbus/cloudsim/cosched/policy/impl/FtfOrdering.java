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
 * Finish-time fairness (Themis-style), Synergy's approximation ({@code Job.finish_time_fair_metric}):
 * (time since arrival + remaining time) / whole run time at the fixed share. Synergy's key
 * ({@code schedulers/ftf_synergy_new.py}): (starved first, that metric, arrival). Like SRTF and LAS it marks
 * a job not run for 1000 hours, taking effect from the next round.
 */
public final class FtfOrdering implements OrderingPolicy {

    private final RemainingEstimate estimate;
    private final Starvation starvation = new Starvation();

    public FtfOrdering(RemainingEstimate estimate) {
        this.estimate = estimate;
    }

    @Override
    public List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel tm) {
        Map<Integer, Double> key = new HashMap<>();
        for (JobInfo j : jobs) {
            double whole = j.totalTimeAtProportionalShare();
            if (!(whole > 0)) throw new IllegalStateException("FTF needs job " + j.id() + "'s run time at the fixed share");
            key.put(j.id(), (state.now() - j.arrivalTime() + estimate.remainingS(j, state, tm)) / whole);
        }
        List<JobInfo> l = new ArrayList<>(jobs);
        l.sort(Comparator.<JobInfo>comparingInt(j -> starvation.isBoosted(j) ? 0 : 1)
                .thenComparingDouble(j -> key.get(j.id()))
                .thenComparingDouble(JobInfo::arrivalTime)
                .thenComparingInt(JobInfo::id));
        starvation.update(jobs, state.now());
        return l;
    }
}
