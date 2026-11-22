# Stacked CPU-GPU CloudSim Scheduler

A pluggable CPU-GPU co-scheduling module for **CloudSim 7G**. It models a shared GPU training cluster and compares schedulers, including how many CPU cores each training job gets. Published results (Synergy, OSDI '22) are reproduced first, and that reproduction is how the tool is shown to be correct.

Cloud Computing course project. This repository is a fork of [CloudSim 7G](https://github.com/cloudslab/cloudsim) (v7.0.1); CloudSim's own README is kept in [docs/cloudsim/README-CloudSim.md](docs/cloudsim/README-CloudSim.md).

## Team

- Chirag ([@Chirag1216](https://github.com/Chirag1216))
- Hamiz ([@hMiZ-STD](https://github.com/hMiZ-STD))

## Why CPU allocation matters

Training jobs need CPU cores to decode and prepare data for the GPU. Most schedulers give every job a fixed CPU:GPU ratio. Synergy showed that jobs need anywhere from about 1 to 9+ cores per GPU, and that sizing CPUs per job cuts average job completion time by up to 3.4×.

## Layout

| Path | What it is |
|---|---|
| `modules/cloudsim` | CloudSim 7G core (unchanged) |
| `modules/cloudsim-examples` | CloudSim examples (unchanged) |
| `modules/cloudsim-gpu` | GPU layer: `gpu.api`, the contract with the scheduler, and its CloudSim implementation (`gpu`) |
| `modules/cloudsim-cosched` | Co-scheduling framework: core types, performance models, workload, scheduling pipeline, metrics |
| `prototypes/` | Check 1 feasibility prototypes, kept as a historical record |
| `docs/cloudsim/` | CloudSim's own README, changelog, UML diagrams and documentation |
| `custom_log.properties` | Logging config read by some CloudSim examples; must stay in the repository root |
| `reports/` | One LaTeX report per implementation phase |
| `tools/` | Python tools: Synergy profile converter, job extractor, Philly prep, run comparator |
| `presets/` | Converted profiles, Synergy reference JCTs, experiment presets (`smoke/`) |
| `docs/archive/` | Earlier planning notes (CloudSim Plus era), superseded by the CloudSim 7G architecture |

## Architecture (five layers)

1. **L1 GPU layer:** GPU servers, a guest per job part, training progress.
2. **L2 Performance models:** throughput by (model, GPU type, cores), locality penalty, power.
3. **L3 Workload:** job traces (Philly, Alibaba PAI) and Synergy's Poisson generator for validation.
4. **L4 Scheduling pipeline:** ordering, then allocation, then placement, then lifecycle. Each round it builds a `RoundPlan` that the GPU layer applies.
5. **L5 Observation:** per-job metrics, energy and CO₂, and comparison across schedulers.

## Build

Requires Java 21 and Maven 3.9+.

```bash
mvn install          # builds every module and runs all tests
```

## Run an experiment

```bash
mvn install -DskipTests
mvn -q -pl modules/cloudsim-cosched dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "modules/cloudsim-cosched/target/classes:$(cat cp.txt)" \
    org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner presets/smoke/smoke.yaml
```

Results go to `out/<experiment>/<candidate>/seed-<seed>/jobs.csv` and `manifest.json`. File formats: [docs/formats.md](docs/formats.md).

## Status

| Phase | Content | State |
|---|---|---|
| 0 | Maven modules, dependencies | Done |
| 1a | Core types, `gpu.api` contract, stub GPU layer | Done ([report](reports/phase-0-1a-foundations.tex)) |
| 1b | Real GPU layer: gang-allocated GPUs, work-based progress, power | Done ([report](reports/phase-1b-gpu-layer.tex)) |
| 1c | Formats, profiles, throughput and rate models, workload and labellers | Done ([report](reports/phase-1c-formats-models-workload.tex)) |
| 2 | Vertical slice: broker, FIFO, fixed share, STRICT placement, first end-to-end run | Done ([report](reports/phase-2-vertical-slice.tex)) |
| 3 | Synergy Greedy / Tune, round-based lifecycle | Planned |
| 4 | Validation against Synergy (mean JCT within 10%) | Planned |
