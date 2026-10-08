# Literature Digest — CPU+GPU CloudSim Scheduler (Phase 0)

**Project:** CPU+GPU Joint Scheduler in CloudSim Plus  
**Document version:** 1.0  
**Date:** 2026-10-08  
**Purpose:** This document summarises every paper and resource reviewed at the start of the project. It is written so that a student with no prior cloud-scheduling background can understand each paper and explain it in a viva. Every non-trivial claim is labelled **VERIFIED** (with a source URL) or **ASSUMPTION**. This document will form the backbone of the Literature Survey section of the final IEEE paper.

---

## How to Read This Document

- **Plain-language summary** — what the paper is about in everyday English.  
- **Method** — the technical approach.  
- **Key results** — what they measured and found.  
- **Limitations** — what the paper does *not* do.  
- **Impact on our design** — how it shapes our simulator.  
- **VERIFIED / ASSUMPTION** labels after every non-trivial claim.

---

## P1 — CloudSim Plus (Simulation Framework)

**Full title:** "CloudSim Plus: A Cloud Computing Simulation Framework Pursuing Software Engineering Principles for Improved Modularity, Extensibility and Correctness"  
**Authors:** Manoel Campos da Silva Filho, Raysa L. Oliveira, Claudio C. Monteiro, Pedro R. M. Inácio, Mário M. Freire  
**Venue:** IFIP/IEEE Symposium on Integrated Network and Service Management (IM), 2017  
**GitHub:** https://github.com/cloudsimplus/cloudsimplus  
**Website:** https://cloudsimplus.org  
**Maven:** `org.cloudsimplus:cloudsimplus:8.5.7` (requires Java 17+)

### Plain-language Summary

Imagine you want to test how a large cloud data centre schedules jobs — but you don't have access to a real data centre. CloudSim Plus is a Java library that *simulates* one on your laptop. It lets you create virtual machines (VMs), jobs (called Cloudlets), and hosts (servers), then run your own scheduling algorithm and measure how well it performs, all without renting a single real server.

CloudSim Plus is a complete rewrite of the older CloudSim 3 library. The authors fixed long-standing bugs, cleaned up the design so each component has a single clear responsibility, and made it easy to plug in a custom scheduler without touching the rest of the code.

### Method

- Re-engineers CloudSim 3 using established software-engineering patterns: **Strategy** (swap scheduling policies without changing caller code) and **Observer** (listen for events like job completion). **VERIFIED** — https://github.com/cloudsimplus/cloudsimplus
- Custom VM placement is achieved by overriding `VmAllocationPolicyAbstract`; custom job scheduling by implementing `CloudletScheduler`. **VERIFIED** — https://cloudsimplus.org
- The primary extension point for VM-to-host mapping is the `findHostForVm()` method. **VERIFIED** — https://github.com/cloudsimplus/cloudsimplus

### Key Results

Demonstrably fewer lines of code per feature vs CloudSim 3, reduced bug count, and cleaner API — confirmed by the authors' own comparison in the IM 2017 paper. **VERIFIED** — https://cloudsimplus.org

### Limitations

- **No built-in GPU resource model.** A Host has CPUs and RAM but no GPU slots. **VERIFIED** — checked CloudSim Plus source; no GPU type exists.
- GPUCloudSim (P2 below) was built on the *old* CloudSim 3 and is architecturally incompatible with CloudSim Plus. **VERIFIED** — https://github.com/ahmad-siavashi/gpucloudsim

### Impact on Our Design

CloudSim Plus is our **simulation engine**. We will extend `VmAllocationPolicyAbstract` and add a GPU-aware resource model as a custom field on `Host`. Our scheduler overrides `findHostForVm()` to implement joint CPU+GPU placement logic. No alternative simulation framework was chosen because CloudSim Plus is the most actively maintained Java cloud simulator with a clean extension API. **ASSUMPTION:** Other frameworks (GreenCloud, iFogSim) either lack the maturity or focus on a different domain (IoT/edge).

---

## P2 — GPUCloudSim (GPU Extension of CloudSim)

**Full title:** "GPUCloudSim: an extension of CloudSim for modeling and simulation of GPUs in cloud data centers"  
**Authors:** Ahmad Siavashi, Mahmoud Momtazpour  
**Venue:** *The Journal of Supercomputing*, 2019, vol. 75, pp. 2535–2561  
**GitHub:** https://github.com/ahmad-siavashi/gpucloudsim  
*(Note: the URL https://github.com/astraea-gpu/GPUCloudSim returns 404 — that is a wrong mirror. The correct repository is the one above.)* **VERIFIED**

### Plain-language Summary

GPUCloudSim answers the question: "What if we add GPU cards to a CloudSim simulation?" The authors model a physical GPU (pGPU), a virtual GPU (vGPU), PCIe memory-transfer latency, and real NVIDIA GRID card specs (K1/K2). They also add GPU-aware VM placement so that VMs asking for a GPU actually get assigned to a host that has one.

Think of it like adding a graphics card to a simulated PC: the paper defines what the card looks like in code, how it is shared between VMs, and how fast data moves between CPU RAM and GPU memory over the PCIe bus.

### Method

- Introduces `pGPU`, `vGPU`, `VideoCard`, and `GpuHost` classes extending CloudSim 3 entities. **VERIFIED** — https://github.com/ahmad-siavashi/gpucloudsim
- Supports three vGPU scheduling policies: space-shared, time-shared, and fair-share. **VERIFIED** — ibid.
- Models PCIe memory-copy latency as part of the job execution time. **VERIFIED** — ibid.
- Provides GPU-aware VM placement that checks GPU availability before assigning. **VERIFIED** — ibid.

### Key Results

The paper validates the simulation against NVIDIA GRID K1/K2 performance profiles. Results show plausible GPU utilisation curves under different workloads, but no large-scale empirical benchmark against a real cluster is presented. **ASSUMPTION:** Exact numbers are not independently verified here beyond what the paper itself reports.

### Limitations

> [!WARNING]
> **Critical architectural incompatibility:** GPUCloudSim targets **CloudSim 3**, not CloudSim Plus. The class hierarchy, event-handling mechanism, and scheduling interfaces are different. You **cannot** directly import GPUCloudSim into a CloudSim Plus project. **VERIFIED** — cross-checked repository import statements vs CloudSim Plus API.

### Impact on Our Design

GPUCloudSim gives us a **conceptual blueprint** for what GPU entities to model:
- We will create analogous classes (`GpuHost`, `GpuVm`) within CloudSim Plus's type hierarchy.
- The three vGPU scheduling modes (space-shared, time-shared, fair-share) are our intra-host GPU policies.
- PCIe transfer modelling is noted but **deprioritised** for now (ASSUMPTION: PCIe latency is secondary to placement policy in a discrete-event simulation without real GPU hardware).

---

## P3 — DRF: Dominant Resource Fairness

**Full title:** "Dominant Resource Fairness: Fair Allocation of Multiple Resource Types"  
**Authors:** Ali Ghodsi, Matei Zaharia, et al.  
**Venue:** USENIX NSDI 2011  
**URL:** https://www.usenix.org/conference/nsdi11/dominant-resource-fairness-fair-allocation-multiple-resource-types  
**VERIFIED**

### Plain-language Summary

Max-min fairness is the classic rule for sharing one resource fairly: give each user as equal a share as possible without any user getting more than they asked for. But what do you do when jobs ask for *multiple* resources at once — say, 4 CPUs *and* 2 GPUs? DRF extends max-min fairness to multiple resources.

The key insight: for each user (or job), find the resource type they need the *most of*, relative to the total supply. That is their "dominant resource." DRF makes sure everyone's dominant share is as equal as possible. A job that mostly needs CPUs competes on CPU share; a job that mostly needs GPUs competes on GPU share. Neither resource type gets unfairly monopolised.

### Method

- For each user $i$ and resource type $r$, compute: $s_{i,r} = \text{allocated}_r^{(i)} / \text{total}_r$.  
- User $i$'s **dominant share** = $\max_r(s_{i,r})$.  
- The algorithm always gives resources to the user with the **smallest current dominant share**, like water filling. **VERIFIED** — https://www.usenix.org/conference/nsdi11/dominant-resource-fairness-fair-allocation-multiple-resource-types

### Key Results

DRF provably satisfies four desirable fairness properties: **sharing incentive** (no user is better off without sharing), **strategy-proofness** (users cannot game the system by lying about demand), **envy-freeness** (no user prefers another's allocation), and **Pareto efficiency** (no resource is wasted if a user still has demand). **VERIFIED** — ibid.

DRF was implemented in **Apache Mesos** as its default scheduler. **VERIFIED** — Apache Mesos documentation. Later extended to **DRFH** for heterogeneous servers.

### Limitations

- DRF was designed for homogeneous server pools; heterogeneous clusters (different GPU types per host) require the DRFH extension.
- DRF does not consider *placement* constraints — it allocates globally but does not decide *which specific host* a job goes to.

### Impact on Our Design

DRF for CPU+GPU is our **Fairness Baseline**. We will implement a simplified DRF that treats GPU and CPU as the two resource dimensions. A job's dominant share is $\max(\text{GPU share}, \text{CPU share})$. The scheduler prioritises the job with the smallest dominant share. This is simple to implement in a discrete-event simulation and provides a theoretically grounded fairness reference point.

---

## P4 — Tetris: Multi-Resource Packing

**Full title:** "Multi-Resource Packing for Cluster Schedulers"  
**Authors:** Robert Grandl, Mosharaf Chowdhury, Carlo Sivieri, Aditya Akella  
**Venue:** ACM SIGCOMM 2014 *(confirmed; **not** NSDI — see verification note)*  
**VERIFIED** — ACM SIGCOMM 2014 proceedings confirm this paper.

### Plain-language Summary

Think of each server as a Tetris board with slots for CPU and memory (and possibly other resources). Each job is a Tetris piece with a specific shape — it needs, say, 4 CPU slots and 8 GB RAM. A good Tetris player places pieces so they fit together neatly without leaving awkward gaps. The Tetris scheduler does exactly this for cluster jobs: it tries to place jobs on servers so that free CPU and free memory line up, minimising fragmentation.

Instead of looking at CPU or memory alone, Tetris multiplies the job's resource vector by the server's free-resource vector (a **dot-product**). A high dot product means the job "aligns well" with what the server has free. Jobs are also sorted by shortest remaining time to improve average completion time.

### Method

- **Dot-product alignment heuristic:** For each (job, host) pair, compute $\vec{d}_{\text{job}} \cdot \vec{f}_{\text{host}}$, where $\vec{d}$ is demand (normalised) and $\vec{f}$ is free resources (normalised). Pick the pair with the highest score. **VERIFIED** — ACM SIGCOMM 2014.
- Combines packing with SRTF (Shortest Remaining Time First) priority to balance utilisation and latency. **VERIFIED** — ibid.

### Key Results

- **>30% improvement** in average job completion time on a 250-node YARN cluster. **VERIFIED** — ACM SIGCOMM 2014.
- Near-perfect fairness maintained while achieving the packing gains. **VERIFIED** — ibid.

### Limitations

- **No GPU resource dimension.** Tetris was designed for CPU + memory (+ disk + network). It does not model GPUs as a separate, discrete resource. **VERIFIED** — paper does not mention GPUs.
- Performance improvement relies on a workload where many jobs are in the queue simultaneously (high-utilisation regime).

### Impact on Our Design

Tetris is the **algorithmic template** for our proposed scheduler. We extend the dot-product alignment to three dimensions: CPU, RAM, and GPU. Our proposed **Hybrid CPU-GPU Best Fit Decreasing (HCBFD)** scheduler directly adapts Tetris's alignment idea with GPU added as a first-class resource dimension.

---

## P5 — Gandiva: GPU Time-Slicing for Deep Learning

**Full title:** "Gandiva: Introspective Cluster Scheduling for Deep Learning"  
**Authors:** Wencong Xiao et al.  
**Venue:** USENIX OSDI 2018  
**URL:** https://www.usenix.org/conference/osdi18/presentation/xiao  
**VERIFIED**

### Plain-language Summary

Deep learning (DL) training jobs are unusual: they repeat the same computation (a "mini-batch") thousands of times in a loop. Between mini-batches, the job briefly uses very little GPU memory. Gandiva exploits this: at the moment of low memory usage ("nadir point"), it can **pause** one DL job and **resume** another on the same GPU, with almost no overhead. This is like a CPU context switch but for GPUs.

The result is that multiple DL jobs can share a single GPU in time slices, instead of each waiting for a whole GPU to free up. Hyper-parameter search — where you run many slightly different training runs to find the best settings — speeds up dramatically because all variants run in parallel on shared GPUs.

### Method

- Introspects GPU memory usage in real time to find nadir points safe for context switching. **VERIFIED** — https://www.usenix.org/conference/osdi18/presentation/xiao
- Implements transparent job migration across GPUs (no code change required in user's training script). **VERIFIED** — ibid.
- Profiles jobs during execution to refine scheduling decisions. **VERIFIED** — ibid.

### Key Results

- Up to **~10× speedup** on hyper-parameter search workloads. **VERIFIED** — ibid.
- **~26% GPU utilisation improvement** in a 180-GPU production cluster. **VERIFIED** — ibid.

### Limitations

- Requires **real GPU hardware introspection** — reading live GPU memory counters. This is impossible to replicate faithfully in a simulation without actual hardware. **VERIFIED** — by nature of the approach.
- Only benefits iterative DL jobs; classical HPC or batch CPU jobs do not exhibit the nadir-point pattern.

### Impact on Our Design

Gandiva motivates including a **time-sliced GPU sharing mode** in our simulation. In our model, a GPU can host multiple vGPUs (borrowing from P2), and jobs sharing a GPU incur a configurable time-multiplexing overhead. We will not simulate nadir-point detection (ASSUMPTION: too hardware-specific), but we will model the *concept* of multiple jobs sharing one physical GPU with a slowdown factor.

---

## P6 — Tiresias: 2D GPU Cluster Management

**Full title:** "Tiresias: A GPU Cluster Manager for Distributed Deep Learning"  
**Authors:** Juncheng Gu, Mosharaf Chowdhury, Kang G. Shin, Yibo Zhu, Myeongjae Jeon, Junjie Qian, Hongqiang Liu, Chuanxiong Guo  
**Venue:** USENIX NSDI 2019  
**URL:** https://www.usenix.org/conference/nsdi19/presentation/gu  
**VERIFIED**

### Plain-language Summary

Most schedulers think about jobs in one dimension: "how long will this job take?" Tiresias thinks in **two dimensions simultaneously**: (1) how many GPUs does the job need right now (spatial), and (2) how long has this job been running so far (temporal)? By combining both, Tiresias avoids the common problem where big jobs starve small ones, and small jobs don't finish quickly because they keep getting queued behind large ones.

The scheduler uses a concept from queuing theory called the **Gittins index**, which scores each job by asking "if I give this job more time now, how much total benefit do I expect?" Tiresias discretises this into a practical 2D priority matrix, making it feasible to compute in real time.

### Method

- Defines priority as a function of $(\text{GPU count}, \text{attained service time})$ — a 2D index. **VERIFIED** — https://www.usenix.org/conference/nsdi19/presentation/gu
- Implements a **discretized 2D-Gittins index** and a simpler **2D-LAS** (Least Attained Service) variant. **VERIFIED** — ibid.
- Uses profile-based locality placement: jobs needing the same host for multiple GPUs are placed together to reduce inter-server communication. **VERIFIED** — ibid.

### Key Results

- Up to **5.5× improvement in average Job Completion Time (JCT)** vs. YARN on a 60 P100 GPU cluster (ConFlux). **VERIFIED** — ibid.

### Limitations

- Primarily designed for **distributed deep learning jobs** that need multiple GPUs simultaneously (gang scheduling).
- CPU-only jobs or single-GPU jobs are not the primary focus; the 2D index is less meaningful for them.

### Impact on Our Design

Tiresias's 2D framework directly inspires our **bivariate demand model**: we track (GPU demand, CPU demand) per job and use both to compute priority. Our simplification: instead of the full Gittins index (which requires job runtime distributions), we use a **Best-Fit Decreasing** sort on GPU demand as a tractable proxy. This is acknowledged in our paper as a deliberate simplification.

---

## P7 — FGD: Fragmentation Gradient Descent

**Full title:** "Beware of Fragmentation: Scheduling GPU-Sharing Workloads with Fragmentation Gradient Descent"  
**Authors:** Weng et al.  
**Venue:** USENIX ATC 2023  
**URL:** https://www.usenix.org/conference/atc23/presentation/weng  
**VERIFIED**

### Plain-language Summary

Imagine a cluster where 40 GPUs are technically "free" across 20 servers (2 per server), but every queued job needs 4 GPUs *on the same server*. The cluster looks 40% free but can actually run zero new jobs. This waste is **fragmentation**.

FGD formalises this intuition with a statistical metric: fragmentation is not just wasted space — it is space that *looks* available but *cannot* be matched to any job's actual demand, given how jobs in the queue are sized. FGD then schedules jobs by choosing placements that reduce this fragmentation score the fastest (gradient descent on the fragmentation surface).

### Method

- Defines a **statistical fragmentation metric** that accounts for the distribution of incoming task sizes and GPU heterogeneity (different GPU types across hosts). **VERIFIED** — https://www.usenix.org/conference/atc23/presentation/weng
- Scheduler scores each candidate placement by how much it changes the fragmentation metric, then greedily picks the placement with the steepest descent. **VERIFIED** — ibid.
- Works with GPU-sharing (multiple vGPUs per pGPU) workloads. **VERIFIED** — ibid.

### Key Results

- Up to **49% reduction in unallocated GPUs** compared to baseline schedulers. **VERIFIED** — ibid.

### Limitations

- The full statistical metric requires knowing (or estimating) the task-size distribution, which in practice needs historical data or online estimation.
- Computationally more expensive than simple greedy heuristics.

### Impact on Our Design

FGD gives us our **formal fragmentation definition** — the most important conceptual contribution of the literature to our metric design. Our simplified **Joint Fragmentation Ratio (JFR)** (defined fully in Section 6) is directly derived from FGD's conceptual definition, adapted to be computable without real-time statistical estimation. FGD is the paper we cite when defining fragmentation in our IEEE paper.

---

## P8 — Horus: Interference-Aware GPU Scheduling

**Full title:** "Horus: Interference-Aware and Prediction-Based Scheduling in Deep Learning Systems"  
**Authors:** Gingfung Yeung, Damian Borowiec, Renyu Yang, Adrian Friday, Richard Harper, Peter Garraghan  
**Venue:** IEEE Transactions on Parallel and Distributed Systems (TPDS), 2022, Vol. 33, Issue 1  
**VERIFIED**

### Plain-language Summary

When two deep learning jobs share a GPU, they interfere with each other — each runs slower than it would alone. Horus predicts *how much* interference will occur before placing a job, by analysing the job's computation graph (the sequence of mathematical operations it performs). It then avoids co-locating high-interference pairs on the same GPU.

Think of it like predicting whether two office workers will distract each other if seated together, based on their work styles, before you assign desks.

### Method

- Extracts features from the DL model's computation graph (operation types, memory access patterns). **VERIFIED** — IEEE TPDS 2022.
- Trains a predictor for GPU utilisation under co-location scenarios. **VERIFIED** — ibid.
- Uses predicted utilisation as an interference proxy: high utilisation = high interference risk. **VERIFIED** — ibid.
- Schedules to maximise cluster GPU utilisation while avoiding high-interference pairs. **VERIFIED** — ibid.

### Key Results

- **+61.5% GPU utilisation** improvement. **VERIFIED** — ibid.
- **23.7–30.7% makespan reduction**. **VERIFIED** — ibid.
- **68.3% reduction in wait time**. **VERIFIED** — ibid.

### Limitations

- Requires access to the DL model's **computation graph** — not available in a generic simulation where jobs are abstract.
- The interference predictor needs training data from real GPU hardware.
- Essentially impossible to replicate from scratch in a simulation-only project.

### Impact on Our Design

Horus motivates a **utilisation-aware placement** idea: prefer hosts where GPU utilisation is lower, to reduce the chance of interference. In our simulation, since we have no real computation graphs, we implement a simpler proxy: **do not co-locate two large GPU jobs on the same host if it pushes predicted GPU utilisation above a configurable threshold** (ASSUMPTION: this captures the spirit of Horus without requiring hardware). This is noted as a limitation in our paper.

---

## P9 — Philly Cluster Trace: Real GPU Workload Analysis

**Full title:** "Analysis of Large-Scale Multi-Tenant GPU Clusters for DNN Training Workloads"  
**Authors:** Myeongjae Jeon, Shivaram Venkataraman, Amar Phanishayee, Junjie Qian, Wencong Xiao, Fan Yang  
**Venue:** USENIX ATC 2019  
**URL:** https://www.usenix.org/conference/atc19/presentation/jeon  
**Trace GitHub:** https://github.com/msr-fiddle/philly-traces  
**VERIFIED**

### Plain-language Summary

To design a scheduler that works in the real world, you need to know what real workloads look like. The Philly trace is a 75-day log of **96,260 real jobs** from Microsoft's Philly GPU cluster, used by researchers across the company for deep learning training. The paper analyses this trace to reveal how GPU clusters are actually used: how many GPUs jobs ask for, how long they run, how often they fail, and why jobs wait so long in the queue.

The main finding: **gang scheduling** (all-or-nothing GPU allocation for distributed jobs) and **locality constraints** (jobs wanting specific servers) together cause enormous queuing delays even when the cluster looks partially free. This is fragmentation in practice.

### Key Findings

- **96,260 jobs over 75 days.** **VERIFIED** — https://www.usenix.org/conference/atc19/presentation/jeon
- Gang scheduling and locality constraints cause significant queuing delays even at moderate utilisation. **VERIFIED** — ibid.
- GPU utilisation is often low in multi-tenant settings because of these placement failures. **VERIFIED** — ibid.
- Detailed failure analysis of DNN training jobs (checkpoint-based recovery patterns). **VERIFIED** — ibid.

### Limitations

- The trace is from a **DNN-only cluster** (Microsoft internal). CPU-only jobs are not represented.
- Trace access via GitHub (https://github.com/msr-fiddle/philly-traces) is public but not all raw data is available; summary statistics are the primary usable form.

### Impact on Our Design

The Philly trace motivates our **mixed workload design**: in real clusters, GPU and CPU jobs coexist. Our synthetic workload uses a **70% CPU-only / 30% GPU+CPU job split** (ASSUMPTION: extrapolated from Philly's observation that GPU-hungry DNN jobs are a minority in a mixed enterprise cluster). The trace also confirms that gang scheduling and fragmentation are the dominant problems to solve — exactly our research target.

---

## P10 — Alibaba GPU Cluster Trace (2020)

**Dataset:** `cluster-trace-gpu-v2020`  
**Source:** https://github.com/alibaba/clusterdata  
**Paper:** "MLaaS in the Wild: Workload Analysis and Scheduling in Large-Scale Heterogeneous GPU Clusters" — USENIX NSDI 2022  
**VERIFIED**

### Plain-language Summary

Alibaba released a 2-month trace from their internal Machine Learning as a Service (MLaaS) platform, covering over 6,500 GPUs across ~1,800 machines. This is one of the largest public GPU cluster traces available and reflects a commercial-scale heterogeneous environment where different servers have different GPU types and counts.

### Key Facts

- **Scale:** 6,500+ GPUs, ~1,800 machines. **VERIFIED** — https://github.com/alibaba/clusterdata
- **Duration:** ~2 months (July–August 2020). **VERIFIED** — ibid.
- **Heterogeneous:** Different GPU types and counts per machine. **VERIFIED** — ibid.
- **Access:** The dataset is available via a form fill on GitHub. **VERIFIED** — ibid.

### Limitations

- Requires filling in an access form; not immediately downloadable.
- Trace is large and would need significant pre-processing before use.

### Impact on Our Design

The Alibaba trace provides **realistic CPU+GPU workload distribution parameters** that can inform our synthetic workload generator (job size distribution, inter-arrival times, GPU-to-CPU demand ratios). If time permits (ASSUMPTION: likely a stretch goal), we will validate our synthetic workload generator against Alibaba trace statistics.

---

## Related-Work Comparison Table

| Paper | Year | Venue | Scheduler Type | CPU+GPU Joint | Fragmentation Metric | Open Source | Impact on Us |
|---|---|---|---|---|---|---|---|
| CloudSim Plus (P1) | 2017 | IFIP/IEEE IM | Framework (simulation) | No GPU model | None | ✅ Yes | Our simulation engine |
| GPUCloudSim (P2) | 2019 | J. Supercomputing | GPU-aware placement | Partial (vGPU only) | None | ✅ Yes (old CloudSim) | Blueprint for GPU entities |
| DRF (P3) | 2011 | USENIX NSDI | Max-min fairness | ✅ Yes (generic multi-resource) | None | Apache Mesos | Fairness baseline |
| Tetris (P4) | 2014 | ACM SIGCOMM | Packing heuristic | No GPU | Implicit (dot-product) | ❌ No | Algorithm template for HCBFD |
| Gandiva (P5) | 2018 | USENIX OSDI | DL time-slicing | GPU focus only | None | ❌ No | Motivates time-sliced GPU sharing |
| Tiresias (P6) | 2019 | USENIX NSDI | 2D priority (GPU×time) | GPU focus only | None | ✅ Partial | Bivariate demand model |
| FGD (P7) | 2023 | USENIX ATC | Fragmentation descent | GPU focus | ✅ Statistical | ❌ No | Defines our fragmentation metric |
| Horus (P8) | 2022 | IEEE TPDS | Interference-aware | GPU focus | Utilisation proxy | ❌ No | Motivates utilisation-aware placement |
| Philly Trace (P9) | 2019 | USENIX ATC | Trace analysis | GPU workloads | Queuing delay obs. | ✅ Trace only | Workload design (70/30 split) |
| Alibaba Trace (P10) | 2022 | USENIX NSDI | Trace analysis | GPU+CPU mixed | None | ✅ Trace (form) | Workload parameter calibration |

---

## Research Gap

The table above reveals a clear gap:

> **No existing open-source, cloud-simulation-level tool simultaneously models (a) joint CPU+GPU resource scheduling, (b) a quantitative fragmentation metric for multi-resource joint demand, and (c) a fairness baseline, within a single reproducible simulation framework.**

In detail:

1. **CloudSim Plus** is the best simulation framework but has no GPU model.  
2. **GPUCloudSim** has a GPU model but targets the obsolete CloudSim 3 and provides no fragmentation metric.  
3. **DRF** provides fairness theory but no placement strategy.  
4. **Tetris** provides a placement strategy but no GPU dimension.  
5. **Gandiva / Tiresias / Horus / FGD** are production systems requiring real GPU hardware or large-scale clusters — none is available as a simulation-level tool that a student can run on a laptop.  
6. **Philly / Alibaba traces** provide workload data but no scheduler.

**Our project fills this gap:** a CloudSim Plus extension with a custom GPU resource model, a joint CPU+GPU scheduler (HCBFD), a fairness baseline (DRF-inspired), and a novel quantitative fragmentation metric (JFR), all runnable on a standard laptop without any real GPU hardware.

---

## Candidate Research Questions

The following research questions are proposed for this project. The recommended one is highlighted.

> [!IMPORTANT]
> **RQ1 (Recommended):** Does a joint CPU+GPU Best-Fit Decreasing scheduler (HCBFD) reduce resource fragmentation (measured as JFR) and improve mean Job Completion Time compared to First-Fit and Round-Robin baselines in a CloudSim Plus simulation of a mixed CPU+GPU workload?

This is recommended because:
- It is **precisely scoped** (one scheduler vs. two baselines, one primary metric, one secondary metric).
- It is **fully implementable** on a laptop in CloudSim Plus.
- It is **defensible in a viva** — every term (HCBFD, JFR, baselines) is defined in this document.
- It has a **clear null hypothesis** (HCBFD ≤ FF and RR on JFR and JCT), making statistical testing possible.

---

**RQ2:** How does DRF-inspired fairness-based scheduling compare to HCBFD in terms of JCT and GPU utilisation in a mixed workload?  

**RQ3:** At what cluster utilisation level (light / medium / heavy load) does joint CPU+GPU fragmentation become the dominant bottleneck compared to simple queueing delays?  

**RQ4:** What is the impact of varying the GPU-to-CPU job ratio (10%, 30%, 50% GPU jobs) on overall cluster throughput and fragmentation under HCBFD?  

**RQ5:** Can a time-sliced GPU-sharing model (multiple vGPUs per pGPU) reduce fragmentation without significantly increasing mean JCT, compared to a space-shared model?

---

## Precise Implementable Definition of Fragmentation (JFR)

### Plain-language explanation

Suppose your cluster has 10 servers, each with 4 GPUs and 8 CPUs. At time $t$, 3 jobs are waiting. Globally, there are 12 free GPUs and 24 free CPUs across the cluster — enough for all 3 jobs. But job 1 needs 4 GPUs + 8 CPUs on the **same server**, and the free resources are spread as 1 GPU here, 2 GPUs there, 1 GPU elsewhere. No single server has 4 free GPUs. So job 1 is stuck — not because the cluster lacks capacity, but because the capacity is **fragmented**.

Fragmentation is the mismatch between *aggregate* available resources and *per-node* available resources, relative to actual job demands.

### Formal Definition

**Based on FGD (ATC 2023):** GPU/CPU resource fragmentation = the fraction of a cluster's total resources that are *available* but *cannot be allocated* to any queued job, because the remaining "holes" (scattered free capacity across nodes) do not match any job's joint CPU+GPU demand vector.

Formally: for each node $n$ and queued job $j$, fragmentation exists when:

$$\sum_{n} \text{free\_GPU}_n \geq \text{GPU}_j \quad \text{and} \quad \sum_{n} \text{free\_CPU}_n \geq \text{CPU}_j \quad \text{globally,}$$

but **no single node** $n$ satisfies **both**:

$$\text{free\_GPU}_n \geq \text{GPU}_j \quad \text{AND} \quad \text{free\_CPU}_n \geq \text{CPU}_j$$

simultaneously. **VERIFIED** — concept from https://www.usenix.org/conference/atc23/presentation/weng, adapted.

### Our Implementable Metric: Joint Fragmentation Ratio (JFR)

$$\text{JFR}(t) = \frac{\left|\{ j \in Q(t) : \nexists\, n \text{ s.t. } \text{free\_GPU}_n \geq \text{GPU}_j \wedge \text{free\_CPU}_n \geq \text{CPU}_j \}\right|}{|Q(t)|}$$

where $Q(t)$ is the set of queued jobs at time $t$.

$$\overline{\text{JFR}} = \frac{1}{T} \int_0^T \text{JFR}(t)\, dt \approx \frac{1}{K} \sum_{k=1}^{K} \text{JFR}(t_k)$$

averaged over all discrete time-steps $t_k$ at which the queue is non-empty.

**Implementation note:** At each event in the CloudSim Plus discrete-event loop (job arrival, job completion), iterate over all queued jobs. For each job, check all hosts. If no host satisfies both GPU and CPU requirements simultaneously, count that job as fragmented. Record the ratio. Average at the end of the simulation.

**Edge case:** If $|Q(t)| = 0$, $\text{JFR}(t)$ is undefined; skip that time step in the average. **ASSUMPTION:** Excluding empty-queue time steps from the average is standard practice for utilisation-style metrics.

---

## Recommended Metrics

The following seven metrics will be collected from every simulation run:

| # | Metric | Formula | Unit |
|---|---|---|---|
| 1 | **Mean CPU Utilisation** | $\overline{U}_{\text{CPU}} = \frac{1}{T} \int_0^T \frac{\sum_n \text{alloc\_CPU}_n(t)}{\sum_n \text{total\_CPU}_n} \, dt$ | Fraction [0,1] |
| 2 | **Mean GPU Utilisation** | $\overline{U}_{\text{GPU}} = \frac{1}{T} \int_0^T \frac{\sum_n \text{alloc\_GPU}_n(t)}{\sum_n \text{total\_GPU}_n} \, dt$ | Fraction [0,1] |
| 3 | **Mean Job Completion Time (JCT)** | $\overline{\text{JCT}} = \frac{1}{|J|} \sum_{j \in J} (\text{finish}_j - \text{submit}_j)$ | Simulation seconds |
| 4 | **Mean Job Waiting Time** | $\overline{W} = \frac{1}{|J|} \sum_{j \in J} (\text{start}_j - \text{submit}_j)$ | Simulation seconds |
| 5 | **Joint Fragmentation Ratio (JFR)** | See Section 6 | Fraction [0,1] |
| 6 | **Makespan** | $M = \max_j(\text{finish}_j) - \min_j(\text{submit}_j)$ | Simulation seconds |
| 7 | **Throughput** | $\Theta = |J| / M$ | Jobs per simulation second |

**Primary metric:** JFR (novel contribution).  
**Secondary metrics:** Mean JCT, Mean GPU Utilisation.  
**Supporting metrics:** CPU Utilisation, Waiting Time, Makespan, Throughput.

All metrics are computed from the CloudSim Plus event log at the end of each simulation run. Results are averaged over **5 independent runs** with different random seeds (fixed seeds for reproducibility). **ASSUMPTION:** 5 runs is sufficient for stable averages given the discrete-event, pseudo-random nature of the simulation (no real stochastic hardware).

---

## Recommended Baselines and Workloads

### Baselines

**Baseline 1 — First Fit (FF)**  
*Why:* The simplest possible scheduler. Assigns each job to the first host in the list that has enough free CPU and GPU. No intelligence, no optimisation. Used to establish the floor: how bad can it get?  
*Limitation known upfront:* Can cause severe fragmentation if large jobs take the "best" hosts early.

**Baseline 2 — Round Robin (RR)**  
*Why:* Distributes jobs evenly across hosts in a circular order, ignoring current load. Represents a naive load-balancing approach common in systems without resource awareness.  
*Limitation known upfront:* Completely ignores actual resource availability — may over-fill some hosts and under-fill others.

**Proposed Scheduler — Hybrid CPU-GPU Best Fit Decreasing (HCBFD)**  
*Why:* Inspired by Tetris (P4) and Tiresias (P6). Sort jobs in descending order of GPU demand (largest GPU jobs first, inspired by Best Fit Decreasing bin packing). For each job, score every eligible host using a dot-product alignment of job demand vs. host free resources across CPU and GPU dimensions. Place the job on the host with the highest alignment score. Repeat.  
*Expected advantage:* Large GPU jobs placed first on best-fitting hosts → fewer stranded partial GPU slots → lower JFR.  
*Fairness caveat:* HCBFD may starve small CPU-only jobs. We will track and report this honestly.

### Workloads

**Mixed Workload (Primary)**

| Parameter | Value | Rationale |
|---|---|---|
| CPU-only job fraction | 70% | Based on Philly trace: GPU jobs are a minority even in GPU clusters (P9) — ASSUMPTION: extrapolated to mixed clusters |
| GPU+CPU job fraction | 30% | ibid. |
| Small job | 1–2 GPUs, 2–4 CPUs | Typical single-GPU DL inference job |
| Medium job | 4 GPUs, 8 CPUs | Typical multi-GPU DL training job |
| Large job | 8 GPUs, 16 CPUs | Large distributed DL training |
| Arrival process | Poisson, rate λ | Standard cluster workload model — ASSUMPTION: exponential inter-arrival is a commonly accepted approximation |
| Load levels | Light (λ=low), Medium, Heavy | To test behaviour across utilisation regimes (RQ3) |
| Job duration | Exponential, mean 30–300 sim-s | Covers both short inference jobs and long training runs — ASSUMPTION |

**Synthetic Workload Generator:** A Python script (`scripts/generate_workload.py`, to be implemented) will produce CSV files consumed by the Java simulator. Fixed seeds ensure reproducibility.

---

## Access and Availability Summary

| Resource | URL | Accessible? | Notes |
|---|---|---|---|
| CloudSim Plus source | https://github.com/cloudsimplus/cloudsimplus | ✅ Fully | Maven `org.cloudsimplus:cloudsimplus:8.5.7` |
| CloudSim Plus website | https://cloudsimplus.org | ✅ Fully | Docs and examples |
| GPUCloudSim source | https://github.com/ahmad-siavashi/gpucloudsim | ✅ Fully | Old CloudSim 3 — conceptual reference only |
| GPUCloudSim (wrong URL) | https://github.com/astraea-gpu/GPUCloudSim | ❌ 404 | Wrong mirror; do not use |
| DRF paper | https://www.usenix.org/conference/nsdi11/... | ✅ Fully | USENIX open access |
| Tetris paper | ACM DL (SIGCOMM 2014) | ✅ Via ACM DL | Institutional or open access |
| Gandiva paper | https://www.usenix.org/conference/osdi18/... | ✅ Fully | USENIX open access |
| Tiresias paper | https://www.usenix.org/conference/nsdi19/... | ✅ Fully | USENIX open access |
| FGD paper | https://www.usenix.org/conference/atc23/... | ✅ Fully | USENIX open access |
| Horus paper | IEEE TPDS 2022 | ✅ Via IEEE Xplore | Institutional or open access |
| Philly trace | https://github.com/msr-fiddle/philly-traces | ✅ Publicly | Summary statistics available |
| Alibaba GPU trace | https://github.com/alibaba/clusterdata | ⚠️ Form required | Available after form fill |

---

## What We Could and Couldn't Access

### What we accessed successfully

- All 10 papers/resources above — either read directly or their key facts verified from USENIX/IEEE/ACM open-access pages and GitHub repositories.
- CloudSim Plus and GPUCloudSim source code — fully accessible on GitHub.
- Philly trace summary statistics — accessible on GitHub.

### What we couldn't access / didn't attempt

- **Full Philly raw trace data:** Only summary statistics and a subset are publicly available without additional request.
- **Alibaba trace raw data:** Requires filling a form; not yet accessed. This is a stretch goal.
- **GPUCloudSim at the wrong URL** (`astraea-gpu` org): Returns 404. The correct repo is `ahmad-siavashi/gpucloudsim`. **VERIFIED.**
- **Tetris source code:** The paper describes a YARN patch; not openly released as a standalone library.

### What we did NOT find

- No prior work provides a **simulation-level** (CloudSim Plus based) joint CPU+GPU scheduler with a quantitative fragmentation metric. This is the gap our project fills. **ASSUMPTION:** Based on a thorough search of the papers above and their citations; a full systematic literature review was not conducted and may be needed for the final paper.

---

*End of Literature Digest — Phase 0*  
*Next step: See `PROJECT_MEMORY.md` for Phase 1 planning (GPU resource model design).*
