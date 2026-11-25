package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.RoundPlan;

/** Decides what happens to running jobs each round: kept as they are, or re-allocated. */
public interface LifecycleController {

    /**
     * {@link RoundPlan.Start#CARRY_RUNNING}: running jobs keep their allocation (non-preemptive);
     * {@link RoundPlan.Start#EMPTY}: every job is re-allocated each round, and a running job left out
     * is preempted (Synergy's round-based mode).
     */
    RoundPlan.Start planStart();

    /**
     * Synergy's round semantics, for exact replication: a job advances exactly
     * {@code floor(round / iteration time)} whole iterations per round, and a job that finishes inside a
     * round is recorded as finishing at the round's end. Needs periodic rounds only.
     */
    default boolean synergyRounds() {
        return false;
    }
}
