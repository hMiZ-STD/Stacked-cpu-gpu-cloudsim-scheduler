package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;

/** One candidate scheduler: the plug-ins that make up its pipeline. */
public record SchedulerSetup(SchedulingTrigger trigger, OrderingPolicy ordering, AllocationPolicy allocation,
                             PlacementPolicy placement, LifecycleController lifecycle) {
}
