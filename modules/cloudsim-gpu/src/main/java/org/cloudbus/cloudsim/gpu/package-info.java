/**
 * The GPU layer (L1) on CloudSim 7G: {@link org.cloudbus.cloudsim.gpu.GpuHost} servers with
 * gang-allocated GPUs, one {@link org.cloudbus.cloudsim.gpu.GpuVm} guest per job part,
 * work-based progress in {@link org.cloudbus.cloudsim.gpu.GpuCloudletScheduler}, and
 * {@link org.cloudbus.cloudsim.gpu.GpuPlanExecutor}, which applies the scheduler's commands.
 * Entry point: {@link org.cloudbus.cloudsim.gpu.CloudSimGpuLayer}.
 */
package org.cloudbus.cloudsim.gpu;
