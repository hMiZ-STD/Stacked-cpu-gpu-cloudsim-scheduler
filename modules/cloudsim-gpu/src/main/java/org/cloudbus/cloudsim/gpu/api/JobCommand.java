package org.cloudbus.cloudsim.gpu.api;

import java.util.List;

/**
 * One change the scheduler asks the GPU layer to make to one job. A round's commands are
 * applied together by {@link PlanExecutor#apply(List)}.
 *
 * <p>Rate is the job's speed in work units (iterations) per simulated second, already including
 * throughput, locality and any interference factor. The GPU layer turns it into CloudSim speed;
 * it never computes throughput itself (the "rate-to-speed split").
 *
 * <p>GPU busy fraction is the share of time the job's GPUs compute rather than wait for input:
 * {@code throughput(c) / throughput(c*)}, where c* is the core count at which the job stops
 * speeding up. It drives GPU power only; it does not change progress.
 */
public sealed interface JobCommand {

    int jobId();

    /** Start a waiting job (first start or resume) with {@code remainingWork} left. */
    record Start(int jobId, double remainingWork, List<PartSpec> parts, double rate, double gpuBusyFraction)
            implements JobCommand {
        public Start {
            parts = List.copyOf(parts);
            if (parts.isEmpty() || !(remainingWork > 0) || !(rate > 0))
                throw new IllegalArgumentException("invalid start for job " + jobId);
            checkBusy(jobId, gpuBusyFraction);
        }

        /** GPUs fully busy. */
        public Start(int jobId, double remainingWork, List<PartSpec> parts, double rate) {
            this(jobId, remainingWork, parts, rate, 1.0);
        }
    }

    /**
     * Change a running job's parts and/or rate. If every part stays on the same server with the
     * same GPUs, the guests are resized in place; otherwise the job is moved (stop, then start
     * with its remaining work), at no extra cost in this version.
     */
    record Resize(int jobId, List<PartSpec> parts, double rate, double gpuBusyFraction) implements JobCommand {
        public Resize {
            parts = List.copyOf(parts);
            if (parts.isEmpty() || !(rate > 0)) throw new IllegalArgumentException("invalid resize for job " + jobId);
            checkBusy(jobId, gpuBusyFraction);
        }

        /** GPUs fully busy. */
        public Resize(int jobId, List<PartSpec> parts, double rate) {
            this(jobId, parts, rate, 1.0);
        }
    }

    /** Stop a running job and release its resources; its remaining work is kept for a later Start. */
    record Stop(int jobId) implements JobCommand {
    }

    private static void checkBusy(int jobId, double f) {
        if (!(f >= 0 && f <= 1)) throw new IllegalArgumentException("job " + jobId + ": gpu busy fraction must be in [0, 1], got " + f);
    }
}
