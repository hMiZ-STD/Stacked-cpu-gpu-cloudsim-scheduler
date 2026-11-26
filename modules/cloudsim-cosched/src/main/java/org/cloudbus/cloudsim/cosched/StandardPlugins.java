package org.cloudbus.cloudsim.cosched;

import org.cloudbus.cloudsim.cosched.core.PolicyRegistry;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.JointAllocationPlacement;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.RemainingEstimate;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;
import org.cloudbus.cloudsim.cosched.policy.impl.ConsolidatedPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FifoOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.FirstFitPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FtfOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.GpuProportionalAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.LasOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.NonPreemptiveLifecycle;
import org.cloudbus.cloudsim.cosched.policy.impl.RoundBasedLifecycle;
import org.cloudbus.cloudsim.cosched.policy.impl.SrtfOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.SynergyGreedyAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.SynergyTune;
import org.cloudbus.cloudsim.cosched.policy.impl.Triggers;
import org.cloudbus.cloudsim.cosched.workload.ClassSplitLabeler;
import org.cloudbus.cloudsim.cosched.workload.FromTraceLabeler;
import org.cloudbus.cloudsim.cosched.workload.ModelLabeler;
import org.cloudbus.cloudsim.cosched.workload.PoissonWorkload;
import org.cloudbus.cloudsim.cosched.workload.WorkloadGenerator;

import java.util.List;

/**
 * The plug-in library: the only class that names concrete policies (architecture rule 7).
 * Each entry states its parameters with default, range and source. Synergy's scheduler names map to
 * candidates like this (docs/validation-plan.md, section 1.6):
 * {@code X+fair} = gpu_proportional, {@code X} = synergy_greedy, {@code X+tune} = synergy_tune; all three
 * with first_fit placement and round_based lifecycle unless the name contains {@code place}.
 */
public final class StandardPlugins {

    private StandardPlugins() {
    }

    public static PolicyRegistry registry() {
        PolicyRegistry r = new PolicyRegistry();

        // Triggers. round_s default 300: Synergy's round_duration=300.
        r.register(SchedulingTrigger.class, "periodic", p -> Triggers.periodic(p.getDouble("round_s", 300, 1, 86_400)));
        r.register(SchedulingTrigger.class, "event_driven", p -> Triggers.eventDriven());

        // Orderings. estimate default fixed_share: Synergy's "+fair" schedulers; Greedy and Tune use current_or_ideal.
        List<String> estimates = List.of("fixed_share", "current_or_ideal");
        r.register(OrderingPolicy.class, "fifo", p -> new FifoOrdering());
        r.register(OrderingPolicy.class, "srtf", p -> new SrtfOrdering(RemainingEstimate.parse(p.getChoice("estimate", "fixed_share", estimates))));
        // largest_first default false; Synergy sets it only for LAS+tune (las_synergy_new.py), see OrderingPolicy.
        r.register(OrderingPolicy.class, "las", p -> new LasOrdering(p.getBoolean("largest_first", false)));
        r.register(OrderingPolicy.class, "ftf", p -> new FtfOrdering(RemainingEstimate.parse(p.getChoice("estimate", "fixed_share", estimates))));

        // Allocation. gpu_proportional = Synergy "+fair"; synergy_greedy = no suffix; synergy_tune = "+tune".
        r.register(AllocationPolicy.class, "gpu_proportional", p -> new GpuProportionalAllocation());
        r.register(AllocationPolicy.class, "synergy_greedy", p -> new SynergyGreedyAllocation());
        r.register(JointAllocationPlacement.class, "synergy_tune", p -> new SynergyTune());

        // Placement. consolidated default strict (Philly's guidance); soft = Synergy's SYNERGY_PLACEMENT.
        // first_fit default soft = Synergy's SYNERGY_RANDOM, used by its FIFO+tune / FIFO+fair / FIFO.
        r.register(PlacementPolicy.class, "consolidated",
                p -> new ConsolidatedPlacement(p.getChoice("locality", "strict", List.of("strict", "soft")).equals("soft")));
        r.register(PlacementPolicy.class, "first_fit",
                p -> new FirstFitPlacement(p.getChoice("locality", "soft", List.of("strict", "soft")).equals("soft")));

        // Lifecycle. synergy_rounds default false: continuous progress; true reproduces Synergy's rounds exactly.
        r.register(LifecycleController.class, "non_preemptive", p -> new NonPreemptiveLifecycle());
        r.register(LifecycleController.class, "round_based", p -> new RoundBasedLifecycle(p.getBoolean("synergy_rounds", false)));

        // Model labellers. split default (34, 33, 33): Synergy's runner.py default model_class_split.
        r.register(ModelLabeler.class, "from_trace", p -> new FromTraceLabeler());
        r.register(ModelLabeler.class, "class_split", p -> {
            List<Double> s = p.getDoubleList("split", List.of(34.0, 33.0, 33.0), 3, 0, 100);
            return ClassSplitLabeler.synergy(s.get(0), s.get(1), s.get(2));
        });

        // Workload generators. No default load or job count: they define the experiment, so they must be written.
        // source synthetic = Synergy's synthetic recipe (helpers/utils.py); resample = rows drawn from a job CSV.
        r.register(WorkloadGenerator.class, "poisson", p -> {
            double jph = p.requireDouble("jobs_per_hour", 1e-6, 1e6);
            int jobs = p.requireInt("jobs", 1, 10_000_000);
            if (p.getChoice("source", "synthetic", List.of("synthetic", "resample")).equals("synthetic"))
                return PoissonWorkload.synthetic(jph, jobs, p.getChoice("gpus", "single", List.of("single", "synergy_mix"))
                        .equals("single") ? PoissonWorkload.GpuMix.SINGLE : PoissonWorkload.GpuMix.SYNERGY_MIX);
            String csv = p.getString("resample_csv", null);
            if (csv == null) throw new IllegalArgumentException("poisson generator: source resample needs resample_csv");
            return PoissonWorkload.resample(jph, jobs, csv);
        });
        return r;
    }
}
