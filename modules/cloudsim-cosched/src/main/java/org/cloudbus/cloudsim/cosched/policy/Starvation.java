package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.JobInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Synergy's starvation guard for SRTF and LAS ({@code schedulers/srtf_synergy_new.py},
 * {@code las_synergy_new.py}): a job that has not run for more than 1000 hours (since its last lease, or
 * since arrival if it never ran) gets priority 1 for good, and priority-1 jobs sort first. Synergy sets the
 * flag after sorting a round, so it takes effect from the next round; {@link #update} is called the same way.
 */
public final class Starvation {

    public static final double LIMIT_S = 1000 * 3600.0;

    private final Set<Integer> boosted = new HashSet<>();

    public boolean isBoosted(JobInfo j) {
        return boosted.contains(j.id());
    }

    /** Marks jobs not run for more than {@link #LIMIT_S} at time {@code now}. */
    public void update(List<JobInfo> jobs, double now) {
        for (JobInfo j : jobs) {
            double since = Double.isNaN(j.lastRunTime()) ? now - j.arrivalTime() : now - j.lastRunTime();
            if (since > LIMIT_S) boosted.add(j.id());
        }
    }
}
