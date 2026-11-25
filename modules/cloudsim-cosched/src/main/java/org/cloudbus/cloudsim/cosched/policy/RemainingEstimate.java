package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;

/**
 * How SRTF and FTF estimate a job's remaining time, as Synergy's {@code Job.remaining_duration(fair)}:
 * <ul>
 *   <li>{@code fixed_share} ("+fair" schedulers): at the fixed share;</li>
 *   <li>{@code current_or_ideal} (Greedy and Tune): at the speed the job is running at, or, if it is
 *       waiting, at its ideal speedup. This is what Synergy does: SRTF and FTF sort <em>before</em>
 *       resetting each job's speedup ({@code srtf_synergy_new.py}, {@code ftf_synergy_new.py}), so a job
 *       that ran at the fixed share last round is estimated at speedup 1.</li>
 * </ul>
 * Speedups are read on the job's own server group if it runs, else the cluster's first group (Synergy's
 * clusters are homogeneous).
 */
public enum RemainingEstimate {
    FIXED_SHARE, CURRENT_OR_IDEAL;

    public static RemainingEstimate parse(String s) {
        return switch (s) {
            case "fixed_share" -> FIXED_SHARE;
            case "current_or_ideal" -> CURRENT_OR_IDEAL;
            default -> throw new IllegalArgumentException("estimate must be 'fixed_share' or 'current_or_ideal', got '" + s + "'");
        };
    }

    public double remainingS(JobInfo j, ClusterState state, ThroughputModel tm) {
        double atShare = j.remainingTimeAtProportionalShare();
        if (this == FIXED_SHARE) return atShare;
        if (j.isRunning()) {
            ServerGroup g = state.server(j.allocation().parts().get(0).serverIndex()).group();
            return atShare / tm.speedup(j, j.allocation().total(), g);
        }
        ServerGroup g = state.spec().serverGroups().get(0);
        return atShare / tm.speedup(j, tm.idealDemand(j, g), g);
    }
}
