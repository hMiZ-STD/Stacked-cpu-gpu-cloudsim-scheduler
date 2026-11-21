package org.cloudbus.cloudsim.gpu.api;

/**
 * A server's state at the current simulation time. Utilisation and power are piecewise
 * constant between scheduling rounds and job ends, so sampling at those moments integrates
 * energy exactly.
 *
 * @param cpuUtil fraction of cores allocated to jobs (allocated cores are assumed fully busy)
 * @param gpuUtil mean busy fraction over all GPUs of the server (unallocated GPUs count as 0)
 * @param powerW  current power draw, or NaN if the server has no {@link PowerSpec}
 */
public record ServerStatus(int index, int usedGpus, double usedCpus, double usedMemGb,
                           double cpuUtil, double gpuUtil, double powerW) {
}
