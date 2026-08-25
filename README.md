# Hybrid CPU-GPU Resource Scheduling and Utilization Analysis for AI Workloads

Cloud Computing course project — extending CloudSim to model joint CPU-GPU scheduling for AI workloads.

## Problem

AI workloads need GPUs while many other jobs only need CPUs. Naive resource allocation in cloud environments leaves one or the other idle, causing fragmentation and poor utilization.

## Approach

Extend CloudSim with GPU as a first-class resource, design a joint CPU-GPU scheduler, and measure utilization and fragmentation compared to a baseline (CPU-only-aware) scheduler.

## Tools

- CloudSim (extended)
- Java
