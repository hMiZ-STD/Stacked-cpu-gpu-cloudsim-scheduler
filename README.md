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
| `reading-docs/` | Full project handbook: onboarding, architecture, commit history, glossary, validation & extension guide ([start here](reading-docs/README.md)) |
| `custom_log.properties` | Logging config read by some CloudSim examples; must stay in the repository root |
| `reports/` | One LaTeX report per implementation phase |
| `tools/` | Python tools: Synergy profile converter, job extractor, Philly prep, run comparator; `synergy_validation/` reruns the Phase 4 references |
| `presets/` | Converted profiles, Synergy reference JCTs, experiment presets (`smoke/`, `synergy-replica/`, `decision-demo/`), Synergy validation suite (`synergy-validation/`) |
| `cosched`, `mvnw` | Run script for experiments; Maven wrapper (no Maven install needed) |
| `docs/archive/` | Earlier planning notes (CloudSim Plus era), superseded by the CloudSim 7G architecture |

## Architecture (five layers)

1. **L1 GPU layer:** GPU servers, a guest per job part, training progress.
2. **L2 Performance models:** throughput by (model, GPU type, cores), locality penalty, power.
3. **L3 Workload:** job traces (Philly, Alibaba PAI) and Synergy's Poisson generator for validation.
4. **L4 Scheduling pipeline:** ordering, then allocation, then placement, then lifecycle. Each round it builds a `RoundPlan` that the GPU layer applies.
5. **L5 Observation:** per-job metrics, energy and CO₂, and comparison across schedulers.

## Build

Requires Java 21 only: `./mvnw` downloads Maven 3.9.9 on first use (or use your own Maven 3.9+ as `mvn`).

```bash
./mvnw install       # builds every module and runs all tests (about 2 minutes)
```

## Run an experiment

```bash
./cosched presets/smoke/smoke.yaml                    # 20 jobs, two candidates, about a second
./cosched presets/synergy-replica/synergy-replica.yaml # Synergy's reference run: Tune vs fixed share vs Greedy
./cosched presets/decision-demo/decision-demo.yaml     # 4 schedulers x 5 seeds x {24, 32} cores, with energy (about 30 s)
```

`./cosched` builds the project when sources have changed, runs every candidate × seed × sweep value, and writes:

- per run, `out/<experiment>/[<sweep point>/]<candidate>/seed-<seed>/`: `jobs.csv` (one row per job), `timeline.csv` (utilisation and power at every change), `manifest.json` (inputs with hashes, every resolved parameter, assumptions, summary), and `workload.csv` for generated workloads;
- the **decision report** in `out/<experiment>/report/`: `decision-report.tex` (compile with pdflatex), its tables in `tables/*.tex` to `\input` elsewhere, and every number as CSV (`runs.csv`, `summary.csv`, `ranking.csv`, `stability.csv`, `sweep_stability.csv`).

File formats, including the energy, generator, sweep and report sections of the YAML: [docs/formats.md](docs/formats.md).

## Status

| Phase | Content | State |
|---|---|---|
| 0 | Maven modules, dependencies | Done |
| 1a | Core types, `gpu.api` contract, stub GPU layer | Done ([report](reports/phase-0-1a-foundations.tex)) |
| 1b | Real GPU layer: gang-allocated GPUs, work-based progress, power | Done ([report](reports/phase-1b-gpu-layer.tex)) |
| 1c | Formats, profiles, throughput and rate models, workload and labellers | Done ([report](reports/phase-1c-formats-models-workload.tex)) |
| 2 | Vertical slice: broker, FIFO, fixed share, STRICT placement, first end-to-end run | Done ([report](reports/phase-2-vertical-slice.tex)) |
| 3 | Synergy Greedy / Tune, first-fit and SOFT placement, round-based lifecycle, SRTF / LAS / FTF, `synergy-replica` preset | Done ([report](reports/phase-3-synergy-policies.tex)) |
| 4 | Validation against Synergy's simulator: 31 configurations, every measured job exact | Done ([report](reports/phase-4-validation.tex)) |
| 5 | Energy and CO₂, seeded workload generator, sweeps, ComparisonRunner and DecisionReport, `./mvnw` and `./cosched` | Done ([report](reports/phase-5-decision-energy.tex)) |
| 6 | DRF and Tetris orderings (validated against Synergy, Fig 13); our own experiments | Planned |
