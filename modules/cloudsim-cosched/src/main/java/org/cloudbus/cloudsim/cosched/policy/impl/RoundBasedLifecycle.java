package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;

/**
 * Synergy's lifecycle: every job's lease ends with the round, and each round re-allocates every runnable
 * job from scratch, so preemption is implicit (and free: Synergy's simulator has no restart cost).
 * {@code synergy_rounds} turns on Synergy's whole-iteration progress and round-end finish times.
 */
public final class RoundBasedLifecycle implements LifecycleController {

    private final boolean synergyRounds;

    public RoundBasedLifecycle(boolean synergyRounds) {
        this.synergyRounds = synergyRounds;
    }

    @Override
    public RoundPlan.Start planStart() {
        return RoundPlan.Start.EMPTY;
    }

    @Override
    public boolean synergyRounds() {
        return synergyRounds;
    }
}
