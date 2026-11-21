# Stacked CPU-GPU CloudSim Scheduler

A pluggable CPU-GPU co-scheduling module for **CloudSim 7G**. It models a shared GPU training cluster and compares schedulers, including how many CPU cores each training job gets. Published results (Synergy, OSDI '22) are reproduced first, and that reproduction is how the tool is shown to be correct.

Cloud Computing course project. This repository is a fork of [CloudSim 7G](https://github.com/cloudslab/cloudsim) (v7.0.1); CloudSim's own README is kept in [README-CloudSim.md](README-CloudSim.md).

## Why CPU allocation matters

Training jobs need CPU cores to decode and prepare data for the GPU. Most schedulers give every job a fixed CPU:GPU ratio. Synergy showed that jobs need anywhere from about 1 to 9+ cores per GPU, and that sizing CPUs per job cuts average job completion time by up to 3.4×.

## Layout

| Path | What it is |
|---|---|
| `modules/cloudsim` | CloudSim 7G core (unchanged) |
| `modules/cloudsim-examples` | CloudSim examples (unchanged) |
| `modules/cloudsim-gpu` | GPU layer: `gpu.api`, the contract with the scheduler, and its implementations |
| `modules/cloudsim-cosched` | Co-scheduling framework: core types, performance models, workload, scheduling pipeline, metrics |
| `modules/Check*.java` | Check 1 feasibility prototypes, kept as a historical record |
| `reports/` | One LaTeX report per implementation phase |
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

## Status

| Phase | Content | State |
|---|---|---|
| 0 | Maven modules, dependencies | Done |
| 1a | Core types, `gpu.api` contract, stub GPU layer | Done ([report](reports/phase-0-1a-foundations.tex)) |
| 1b | Real GPU layer | In progress |
| 1c | Formats, loaders, L2 models, Synergy profile converter | Planned |
| 2 | Vertical slice: FIFO, GPU-proportional, one trace end to end | Planned |
| 3 | Synergy Greedy / Tune, round-based lifecycle | Planned |
| 4 | Validation against Synergy (mean JCT within 10%) | Planned |
