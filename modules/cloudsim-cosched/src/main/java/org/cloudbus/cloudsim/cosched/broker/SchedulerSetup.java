package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.JointAllocationPlacement;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;

/**
 * One candidate scheduler: the plug-ins that make up its pipeline. Exactly one of {@code allocation}
 * (demand, then placement) and {@code joint} (allocation and placement together, e.g. Synergy-Tune) is set.
 */
public record SchedulerSetup(SchedulingTrigger trigger, OrderingPolicy ordering, AllocationPolicy allocation,
                             JointAllocationPlacement joint, PlacementPolicy placement, LifecycleController lifecycle) {

    public SchedulerSetup {
        if ((allocation == null) == (joint == null))
            throw new IllegalArgumentException("set exactly one of an allocation policy and a joint allocation-placement policy");
        if (lifecycle.synergyRounds() && (trigger.roundS() <= 0 || trigger.onArrival() || trigger.onJobEnd()))
            throw new IllegalArgumentException("synergy_rounds needs periodic rounds only (trigger 'periodic')");
    }

    /** A pipeline with a separate allocation policy. */
    public SchedulerSetup(SchedulingTrigger trigger, OrderingPolicy ordering, AllocationPolicy allocation,
                          PlacementPolicy placement, LifecycleController lifecycle) {
        this(trigger, ordering, allocation, null, placement, lifecycle);
    }
}
