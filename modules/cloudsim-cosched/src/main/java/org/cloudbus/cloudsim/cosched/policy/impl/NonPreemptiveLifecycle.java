package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;

/** A started job keeps its allocation until it ends; rounds only place waiting jobs. */
public final class NonPreemptiveLifecycle implements LifecycleController {

    @Override
    public RoundPlan.Start planStart() {
        return RoundPlan.Start.CARRY_RUNNING;
    }
}
