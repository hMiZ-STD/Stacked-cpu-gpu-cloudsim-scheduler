# Developer & Extension Playbook (`EXTENDING.md`)

This guide shows **how to modify, extend, debug, and improve** the codebase safely—whether you are implementing a new scheduling algorithm for Phase 5, adding an interference model, running new trace experiments, or fixing a Python tool.

---

## 1. Golden Rules Never to Break

Before touching Java code, keep these architectural invariants in mind (most are enforced automatically by [ArchitectureTest.java](../modules/cloudsim-cosched/src/test/java/org/cloudbus/cloudsim/cosched/ArchitectureTest.java)):

1. **Never modify `modules/cloudsim` or `modules/cloudsim-examples`.** All extensions live in `cloudsim-gpu` or `cloudsim-cosched`.
2. **Never import `cosched.policy.impl.*` from another policy or from the broker.** Only [StandardPlugins.java](../modules/cloudsim-cosched/src/main/java/org/cloudbus/cloudsim/cosched/StandardPlugins.java) (and tests) may reference concrete policy implementations. Put shared helper math (like `Shares`, `Starvation`, `RemainingEstimate`) in `org.cloudbus.cloudsim.cosched.policy` (not `.impl`).
3. **Policies are pure & read-only on cluster state.** Policies read `ClusterState`, `JobInfo`, `ThroughputModel`, and `RoundPlan`, and write *only* via `RoundPlan.assign(...)` / `remove(...)`. Never touch CloudSim entities from a policy.
4. **Keep `custom_log.properties` in the repository root.** Some upstream CloudSim tests read it from the working directory.
5. **Respect CloudSim's L1 contract.** Never bypass `GpuHost` reservations or `GpuPlanExecutor`'s 3-phase `apply()`; keep in mind CloudSim's `0.01 s` `minTimeBetweenEvents` and `0.02 s` minimum update interval.

---

## 2. How to Add a New Scheduling Policy

Every scheduling policy plugs into one of five interfaces in `org.cloudbus.cloudsim.cosched.policy`:

| If you want to change… | Implement this interface |
|---|---|
| *When* rounds trigger | `SchedulingTrigger` (`roundS()`, `onArrival()`, `onJobEnd()`) |
| *Which order* jobs are tried | `OrderingPolicy` (`order(jobs, state, throughput)`) |
| *How much CPU/RAM* a job asks for | `AllocationPolicy` (`demand(job, group, throughput)`) |
| *Which servers* a job runs on | `PlacementPolicy` (`place(job, demandFn, plan)`, `maxPlaceableGpus(...)`) |
| *Both allocation & placement*, potentially shrinking/moving other jobs | `JointAllocationPlacement` (`place(job, plan, placement, throughput)`) |
| *Whether running jobs stay or re-plan* | `LifecycleController` (`planStart()`) |

### Step-by-Step Example: Adding a New `OrderingPolicy` (e.g., `DRF`)

1. **Create your class in `org.cloudbus.cloudsim.cosched.policy.impl`:**
   ```java
   package org.cloudbus.cloudsim.cosched.policy.impl;

   import org.cloudbus.cloudsim.cosched.core.ClusterState;
   import org.cloudbus.cloudsim.cosched.core.JobInfo;
   import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
   import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;

   import java.util.ArrayList;
   import java.util.Comparator;
   import java.util.List;

   public final class MyCustomOrdering implements OrderingPolicy {
       private final double weight;

       public MyCustomOrdering(double weight) {
           this.weight = weight;
       }

       @Override
       public List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel throughput) {
           List<JobInfo> copy = new ArrayList<>(jobs);
           // Sort without mutating the input list; use state.spec().totalCapacity() if needed
           copy.sort(Comparator.comparingDouble(JobInfo::arrivalTime).thenComparingInt(JobInfo::id));
           return copy;
       }
   }
   ```
   *(If your policy uses parameters from YAML, pass them into the constructor—never parse YAML inside the policy.)*

2. **Register it in `StandardPlugins.java` using `Params`:**
   ```java
   r.register(OrderingPolicy.class, "my_custom", p ->
           new MyCustomOrdering(p.getDouble("weight", 1.0, 0.0, 100.0)));
   ```
   - `Params` automatically validates ranges, records defaults in `manifest.json`, and throws on typos.
   - If you implement `JointAllocationPlacement` (like `SynergyTune`), register it under `JointAllocationPlacement.class`—`ExperimentRunner` automatically checks `reg.has(JointAllocationPlacement.class, name)` before falling back to `AllocationPolicy.class`.

3. **Write unit tests using `PolicyFixtures` (no CloudSim startup needed!):**
   Look at [PolicyFixtures.java](../modules/cloudsim-cosched/src/test/java/org/cloudbus/cloudsim/cosched/policy/PolicyFixtures.java) and [SynergyPoliciesTest.java](../modules/cloudsim-cosched/src/test/java/org/cloudbus/cloudsim/cosched/policy/SynergyPoliciesTest.java). You can construct synthetic clusters (`PolicyFixtures.servers(2)`), jobs (`new PolicyFixtures.J(1, "res18", 2)`), and `RoundPlan`s in milliseconds.

4. **Verify ArchUnit + full build:**
   ```bash
   mvn test -pl modules/cloudsim-cosched
   ```

---

## 3. How to Extend Models & Workloads

### Implementing a Real `InterferenceModel` (Currently `NONE`)
[InterferenceModel.java](../modules/cloudsim-cosched/src/main/java/org/cloudbus/cloudsim/cosched/model/InterferenceModel.java) is already wired into `RateCalculator`:
```java
@FunctionalInterface
public interface InterferenceModel {
    double factor(JobInfo job, Allocation alloc);
}
```
Notice `ExperimentRunner.java` line 108 currently passes `InterferenceModel.NONE`. To add co-location interference (e.g., Philly's ~12.1% slowdown when jobs share a server):
1. Extend `InterferenceModel` (or pass `ClusterState` / active allocations if sibling jobs on the same server are needed).
2. Add an `interference` config block to `ExperimentConfig.Models` (remember `ExperimentConfig` rejects unknown YAML keys until declared!).
3. Register or instantiate it in `ExperimentRunner` and record it in `RunManifest` (`models.interference`).

### Adding New GPU Types or Model Profiles
1. Create `<model>_<gpu_count>.json` following the schema in [docs/formats.md](../docs/formats.md) (strictly increasing `cpus` and `mem_gb_per_gpu`, matching `throughput` matrix dimensions, valid `gpu_type`).
2. Put them in a profiles directory and point `models.profiles_dir` in your experiment YAML to it. `ProfileRepository.requireAll(...)` checks before startup that every `(model, gpu_type, gpu_count)` combination exists.

### Adding a New `ModelLabeler`
Implement `ModelLabeler` (`label(List<JobRecord> jobs)`) in `cosched.workload` and register it in `StandardPlugins.java` under `ModelLabeler.class`.

---

## 4. Designing, Running & Debugging New Experiments

### Creating an Experiment YAML
Copy `presets/smoke/smoke.yaml` and edit:
- **`cluster.server_groups`**: Homogeneous or heterogeneous groups (`A100` + `V100` in the same cluster!).
- **`workload.csv` & `labeller`**: Either trace-style `duration_s` (e.g. from `tools/prep_philly.py`) or `iterations` + `from_trace` / `class_split`.
- **`candidates`**: List every scheduler combination you want to compare side by side on the exact same workload and seeds.

### Debugging Round-by-Round Decisions
If a scheduler behaves unexpectedly, pass `-Dcosched.planTrace=<file>` to log every round's plan (used in Phase 4 to compare all 26,062 rounds of `LAS+tune` against Synergy):
```bash
java -Dcosched.planTrace=out/trace.log -cp "..." \
    org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner my_exp.yaml
```

### Inspecting Outputs
Every `<output_dir>/<candidate>/seed-<seed>/` contains:
- `jobs.csv` — Per-job arrival, first start, finish, JCT, queue delay, number of starts (`starts > 1` indicates preemption/restarts), and final CPU/memory per GPU.
- `manifest.json` — Exact git SHA, config/CSV SHA-256 hashes, resolved plugin params, warnings, round/command counts, and summary metrics (`mean_jct_h`, `p99_jct_h`, `p99.9_jct_h`, `mean_queue_s`, `makespan_s`).

---

## 5. Open Improvement Roadmap

These concrete tasks are documented across `docs/validation-plan.md`, `reports/phase-4-validation.tex`, and `presets/profiles/synergy-v100/README.md` as ready to build next:

1. **Implement `DRF` and `Tetris` Schedulers (Synergy Fig. 13):**
   `ClusterSpec.totalCapacity()` already provides total `(G, C, M)` specifically for DRF and Tetris keys! Implementing them as `OrderingPolicy` / `AllocationPolicy` / `JointAllocationPlacement` plugins lets us replicate Synergy's Fig. 13 comparisons (`W2 (50,0,50)`).
2. **512-GPU Real Philly Arrival Experiment (Synergy Fig. 6, Table 6a):**
   Use `tools/prep_philly.py` on the Philly trace (`64` servers × `8` V100s) and compare `SRTF`, `LAS`, `FIFO` under proportional vs Synergy-Tune.
3. **Phase 5 Custom Experiments:**
   - **Preemption ablation** (comparing `non_preemptive` vs `round_based` vs `synergy_tune` with preemption/restart overheads).
   - **Alibaba PAI (`cluster-trace-gpu-v2020`) robustness check** and **2026 heterogeneous hardware (`A100`/`H100` + `V100`)** experiments.
   - **Energy & CO₂ reporting** (L1 already computes `GpuLayer.status(i)` instantaneous server wattage via `LinearPowerModel` and SPECpower; wiring an energy integrator into `ExperimentRunner`/`JobMetrics` unlocks power & carbon comparisons in `manifest.json`).
4. **Fix `tools/compare_runs.py` Mean Calculation:**
   Restrict both `ours` and `ref` series to `common` job IDs (or add a `--common-only` / steady-state ID filter `4000..4999`) before computing `mean_h`, so exact replays report `0.0000` relative error instead of `3.9%`.
5. **Fix `models-4/5/6` `fair_share` Metadata in `tools/convert_synergy_profiles.py`:**
   Update the informational `fair_share` JSON block for `models-4`, `models-5`, and `models-6` to reflect `4`, `5`, and `6` cores/GPU instead of `3`.
6. **Add a Realistic `InterferenceModel`:**
   Move beyond `InterferenceModel.NONE` using measured co-location slowdown curves (e.g., up to 12.1% from Philly).
