package org.cloudbus.cloudsim.gpu.api;

/**
 * A server's state at the current simulation time. Utilisation and power are piecewise
 * constant between scheduling rounds and job ends, so sampling at those moments integrates
 * energy exactly.
 *
 * @param cpuUtil fraction of cores allocated to jobs (allocated cores are assumed fully busy)
 * @param gpuUtil mean busy fraction over all GPUs of the server (unallocated GPUs count as 0)
 * @param powerW    current power draw of the whole server (CPU + GPUs + base), or NaN if the server
 *                  has no {@link PowerSpec}
 * @param cpuPowerW the CPU's part of {@code powerW} (NaN without a power spec)
 * @param gpuPowerW all GPUs' part of {@code powerW} (NaN without a power spec); the rest is base power
 */
public record ServerStatus(int index, int usedGpus, double usedCpus, double usedMemGb,
                           double cpuUtil, double gpuUtil, double powerW, double cpuPowerW, double gpuPowerW) {
}
