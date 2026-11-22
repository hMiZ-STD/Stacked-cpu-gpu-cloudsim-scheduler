package org.cloudbus.cloudsim.cosched;

import org.cloudbus.cloudsim.cosched.core.PolicyRegistry;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;
import org.cloudbus.cloudsim.cosched.policy.impl.ConsolidatedPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FifoOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.GpuProportionalAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.NonPreemptiveLifecycle;
import org.cloudbus.cloudsim.cosched.policy.impl.Triggers;
import org.cloudbus.cloudsim.cosched.workload.ClassSplitLabeler;
import org.cloudbus.cloudsim.cosched.workload.FromTraceLabeler;
import org.cloudbus.cloudsim.cosched.workload.ModelLabeler;

import java.util.List;

/**
 * The plug-in library: the only class that names concrete policies (architecture rule 7).
 * Each entry states its parameters with default, range and source.
 */
public final class StandardPlugins {

    private StandardPlugins() {
    }

    public static PolicyRegistry registry() {
        PolicyRegistry r = new PolicyRegistry();

        // Triggers. round_s default 300: Synergy's round_duration=300.
        r.register(SchedulingTrigger.class, "periodic", p -> Triggers.periodic(p.getDouble("round_s", 300, 1, 86_400)));
        r.register(SchedulingTrigger.class, "event_driven", p -> Triggers.eventDriven());

        // Ordering.
        r.register(OrderingPolicy.class, "fifo", p -> new FifoOrdering());

        // Allocation. gpu_proportional is Synergy's "+fair".
        r.register(AllocationPolicy.class, "gpu_proportional", p -> new GpuProportionalAllocation());

        // Placement. Only STRICT locality exists so far; SOFT comes with Synergy's policies (Phase 3).
        r.register(PlacementPolicy.class, "consolidated", p -> {
            p.getChoice("locality", "strict", List.of("strict"));
            return new ConsolidatedPlacement();
        });

        // Lifecycle.
        r.register(LifecycleController.class, "non_preemptive", p -> new NonPreemptiveLifecycle());

        // Model labellers. split default (34, 33, 33): Synergy's runner.py default model_class_split.
        r.register(ModelLabeler.class, "from_trace", p -> new FromTraceLabeler());
        r.register(ModelLabeler.class, "class_split", p -> {
            List<Double> s = p.getDoubleList("split", List.of(34.0, 33.0, 33.0), 3, 0, 100);
            return ClassSplitLabeler.synergy(s.get(0), s.get(1), s.get(2));
        });
        return r;
    }
}
