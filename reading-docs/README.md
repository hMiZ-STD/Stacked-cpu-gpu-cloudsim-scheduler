# Stacked CPU–GPU CloudSim Scheduler — Complete Project Guide

Welcome to the **Stacked CPU–GPU CloudSim Scheduler** reading guide. This directory (`reading-docs/`) is designed so that someone starting with **zero prior knowledge** of the project can:

1. Understand the real-world problem and how our simulator models it.
2. Follow the full technical architecture across all five layers (`L1`–`L5`).
3. Trace every commit and design decision from day one through Phase 4 validation.
4. Learn every term, metric, policy, and CloudSim quirk used in the codebase.
5. Confidently build, run, debug, and **extend** the project (for Phase 5 and beyond).

---

## 1. Recommended Reading Order

| Order | Document | Best For |
|---|---|---|
| 1 | **[README.md](./README.md)** *(this file)* | The big picture, a concrete worked example, and quickstart commands |
| 2 | **[GLOSSARY.md](./GLOSSARY.md)** | Plain-English + technical definitions of all terms, policies, and metrics |
| 3 | **[ARCHITECTURE.md](./ARCHITECTURE.md)** | How the Java modules, 5 layers, event loop, and ArchUnit rules work |
| 4 | **[COMMIT_HISTORY.md](./COMMIT_HISTORY.md)** | Every project commit (`588e36ba` → `223b39a0`), what changed, and why |
| 5 | **[TOOLS_AND_VALIDATION.md](./TOOLS_AND_VALIDATION.md)** | Python data tools, presets, and how we matched Synergy 1000/1000 |
| 6 | **[EXTENDING.md](./EXTENDING.md)** | Step-by-step recipes to add new policies, models, experiments, and fixes |

### Existing Reference Docs in the Repo

- [docs/formats.md](../docs/formats.md) — Exact schemas for job CSVs, profile JSONs, and experiment YAMLs.
- [docs/validation-plan.md](../docs/validation-plan.md) — Line-by-line comparison between the Synergy paper and Synergy's actual Python code, plus Phase 4 validation targets.
- [reports/](../reports/) — Phase-by-phase LaTeX reports (`phase-0-1a-foundations.tex` through `phase-4-validation.tex`).
- [docs/archive/](../docs/archive/) — Early CloudSim Plus planning docs and literature digest (superseded by the CloudSim 7G design).
- [docs/cloudsim/](../docs/cloudsim/) — Upstream CloudSim 7G README, changelog, and UML diagrams.

---

## 2. The Problem in Plain English (Zero-to-One)

Imagine a shared university or company cluster full of GPU servers used to train Deep Neural Networks (DNNs). Each server has multiple GPUs (e.g., 8 NVIDIA V100s), CPU cores (e.g., 24 cores), and system memory (e.g., 500 GB RAM).

Most people think only GPUs matter for AI training. **That is false.** Before a GPU can run matrix math on a batch of images, audio, or text, **CPU cores must read, decode, augment, and transfer that data to the GPU**.

- **Image & speech models** (like `res18`, `alexnet`, `deepspeech`) do heavy CPU data preprocessing. If you starve them of CPU cores, the expensive GPU sits idle waiting for data.
- **Language models** (like `gnmt`, `transformer`, `lstm`) do very little CPU preprocessing. They stay fast even with 1 CPU core per GPU.

### Why Traditional ("Fixed Share") Schedulers Fail

Traditional GPU schedulers split a server's CPU and memory **strictly in proportion to GPUs** (for example, on an 8-GPU / 24-CPU / 500 GB server, every 1-GPU job automatically gets `24 / 8 = 3` CPU cores and `500 / 8 = 62.5 GB` RAM).

This hurts both sides:
- A `gnmt` job gets **3 CPU cores** when it only needs **1** → 2 CPU cores sit wasted.
- A `res18` job gets **3 CPU cores** when it wants **9** → it runs **2.4× slower** than it could!

The **Synergy** paper (*Looking Beyond GPUs for DNN Scheduling on Multi-Tenant Clusters*, Mohan et al., OSDI '22) showed that sizing CPU and memory **per job**—instead of using a fixed CPU:GPU ratio—cuts average Job Completion Time (JCT) by up to **3.4×** (and up to **3.7×** at high load in Fig. 9).

### What This Project Built

We built a **pluggable CPU–GPU co-scheduling framework** inside **CloudSim 7G (v7.0.1)** so researchers can:
1. Define arbitrary GPU clusters, workloads, and profiled DNN models in simple YAML/CSV/JSON files.
2. Swap scheduling algorithms independently across 5 plug-in points (**Trigger**, **Ordering**, **Allocation**, **Placement**, **Lifecycle**).
3. Reproduce **Synergy's published simulator (`msr-fiddle/synergy` commit `1bb2bed`)** as a strict correctness baseline—achieving an **exact 1,000/1,000 per-job JCT match (0.0 s max error)** across all **31 tested configurations**.
4. Run new experiments comparing schedulers on metrics like mean/p99/p99.9 JCT, queueing delay, makespan, and power/energy.

---

## 3. A Concrete Worked Example (1 Server, 4 Jobs)

Suppose we have **1 Synergy V100 server**: `8 GPUs`, `24 CPU cores`, `500 GB RAM`.
- **Fixed share per GPU:** `24 / 8 = 3 CPU cores`, `500 / 8 = 62.5 GB RAM`.
- From our real converted profiles (`presets/profiles/synergy-v100/models/`):
  - **`res18` (1 GPU):** Fixed-share iteration time = `0.64 s`. Ideal demand = `9 CPU cores, 62.5 GB RAM` → runs at **`2.4×` speedup** (`0.64 / 2.4 = 0.2667 s/iter`).
  - **`gnmt` (1 GPU):** Fixed-share iteration time = `0.12 s`. Ideal demand = `1 CPU core, 20 GB RAM` → runs at **`1.0×` speedup** (does not benefit from extra CPUs).

Now suppose **four 1-GPU jobs** arrive at `t = 0`: two `gnmt` jobs and two `res18` jobs (actually, let's trace **5 jobs**: two `res18` jobs first, then two `gnmt` jobs, then a third `res18` job — mirroring `SynergyPoliciesTest`):

1. **Under Fixed Share (`gpu_proportional`, Synergy `+fair`):**
   - All 5 jobs get `(1 GPU, 3 CPUs, 62.5 GB)`.
   - Total used: `5 GPUs, 15 CPUs, 312.5 GB`.
   - Every `res18` job runs at **`1.0×` speed** (`0.64 s/iter`) even though **9 CPU cores sit completely idle**!
2. **Under Synergy-Greedy (`synergy_greedy`):**
   - Job 1 (`res18`): asks for ideal `(1 GPU, 9 CPUs, 62.5 GB)` → fits! (`9/24` CPUs used).
   - Job 2 (`res18`): asks for ideal `(1 GPU, 9 CPUs, 62.5 GB)` → fits! (`18/24` CPUs used).
   - Jobs 3 & 4 (`gnmt`): each asks for `(1 GPU, 1 CPU, 20 GB)` → fit! (`20/24` CPUs used).
   - Job 5 (`res18`): asks for **9 CPUs**, only **4 CPUs** remain → **skipped**, even though **3 GPUs are still free**!
3. **Under Synergy-Tune (`synergy_tune`):**
   - Jobs 1–4 get placed at their ideal demands just like Greedy (`20/24` CPUs used).
   - Job 5 (`res18`) tries 9 CPUs (doesn't fit) → drops itself to the fixed share `(1 GPU, 3 CPUs, 62.5 GB)` (still needs 3 CPUs, wait: 4 are free so Job 5 actually fits! If a 6th `res18` arrives with only 1 CPU left, `SynergyTune` shrinks Job 1 from 9 CPUs down to 3 CPUs, freeing 6 CPUs so Job 6 can run immediately!).
   - Result: **zero GPUs sit idle** while CPUs are available, and uncontended `res18` jobs still run `2.4×` faster.

---

## 4. Quickstart

### Prerequisites
- **Java 21** (LTS) and **Maven 3.9+**
- **Python 3.10+** (only needed for `tools/` scripts and `tests/`)

### Build and Run All Tests
```bash
mvn install
```
This compiles all four Maven modules (`cloudsim`, `cloudsim-examples`, `cloudsim-gpu`, `cloudsim-cosched`) and runs the Java test suite (including `ArchitectureTest`, `PlanExecutorContractTest`, and `SynergyValidationTest`).

### Run an Experiment from the CLI
```bash
# 1. Build jars without re-running tests
mvn install -DskipTests

# 2. Export the classpath for cloudsim-cosched (bash/Linux/macOS)
mvn -q -pl modules/cloudsim-cosched dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "modules/cloudsim-cosched/target/classes:$(cat cp.txt)" \
    org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner presets/smoke/smoke.yaml
```

On **Windows PowerShell**:
```powershell
mvn install -DskipTests
mvn -q -pl modules/cloudsim-cosched dependency:build-classpath -Dmdep.outputFile=cp.txt
$cp = Get-Content modules/cloudsim-cosched/cp.txt -Raw
java -cp "modules/cloudsim-cosched/target/classes;$cp" `
    org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner presets/smoke/smoke.yaml
```
*(Note: `build-classpath` writes `cp.txt` inside the selected module directory `modules/cloudsim-cosched/` unless a path is given.)*

Results are written to `<output_dir>/<candidate>/seed-<seed>/jobs.csv` and `manifest.json`.

### Run the Python Tool Tests
```bash
pip install -r tools/requirements.txt
pytest tests -v
```

---

## 5. Project Status & Roadmap

| Phase | Scope | Status | Details |
|---|---|---|---|
| **Phase 0** | Maven modules (`cloudsim-gpu`, `cloudsim-cosched`), dependencies | Done | [phase-0-1a-foundations.tex](../reports/phase-0-1a-foundations.tex) |
| **Phase 1a** | Core immutable types, `gpu.api` contract, `gpu.stub` stand-in | Done | [phase-0-1a-foundations.tex](../reports/phase-0-1a-foundations.tex) |
| **Phase 1b** | Real L1 GPU layer on CloudSim 7G, gang GPUs, work-in-iterations, power | Done | [phase-1b-gpu-layer.tex](../reports/phase-1b-gpu-layer.tex) |
| **Phase 1c** | Formats (`formats.md`), 69 Synergy profiles, L2 models, L3 workload/labellers | Done | [phase-1c-formats-models-workload.tex](../reports/phase-1c-formats-models-workload.tex) |
| **Phase 2** | Vertical slice: `CoSchedulingBroker`, FIFO, fixed share, `consolidated` STRICT, `ExperimentRunner` | Done | [phase-2-vertical-slice.tex](../reports/phase-2-vertical-slice.tex) |
| **Phase 3** | Synergy Greedy/Tune, `first_fit`, `SOFT` locality, `round_based` + `synergy_rounds`, SRTF/LAS/FTF | Done | [phase-3-synergy-policies.tex](../reports/phase-3-synergy-policies.tex) |
| **Phase 4** | Exact validation against Synergy (`1bb2bed`) across 31 configurations (1000/1000 exact) | Done | [phase-4-validation.tex](../reports/phase-4-validation.tex) |
| **Phase 5+** | `DRF` & `Tetris` policies (Fig. 13), 512-GPU Philly arrival run (Fig. 6), `InterferenceModel`, Alibaba PAI / 2026 ablations | **Open** | See [EXTENDING.md](./EXTENDING.md#5-open-improvement-roadmap) |
