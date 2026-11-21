package org.cloudbus.cloudsim.cosched.core;

/**
 * Read-only view of a training job, as policies see it. Policies never change jobs;
 * they only write allocations into a {@link RoundPlan}.
 */
public interface JobInfo {

    int id();

    /** Arrival time in simulated seconds. */
    double arrivalTime();

    /** GPUs requested (gang: all or nothing). */
    int gpus();

    /** Model name used to look up throughput profiles, e.g. "resnet18". */
    String model();

    /** Owning team; "default" when the workload has no teams. */
    String team();

    /** Priority class name; "default" when unused. */
    String priority();

    /** Total work in iterations. */
    double totalWork();

    /** Work left, in iterations, as of the current simulation time. */
    double remainingWork();

    /**
     * Seconds the job would still need at its fixed-share speed. Used by SRTF and Tetris,
     * which need a duration estimate that does not depend on the current allocation.
     */
    double remainingTimeAtProportionalShare();

    /** GPU-seconds of service received so far (LAS / Tiresias ordering). */
    double attainedServiceGpuSeconds();

    /** Time the job first started, or NaN if it has never run. */
    double firstStartTime();

    /** Current allocation, or null if the job is waiting. */
    Allocation allocation();

    default boolean isRunning() {
        return allocation() != null;
    }
}
