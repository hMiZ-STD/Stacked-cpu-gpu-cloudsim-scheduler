package org.cloudbus.cloudsim.cosched.core;

import java.util.List;

/**
 * Read-only view of the cluster at the start of a scheduling round: servers, what is free on
 * each, and every job that has arrived and not finished. Policies read this and the model
 * interfaces only (architecture rule 6).
 */
public interface ClusterState {

    /** Current simulated time in seconds. */
    double now();

    List<Server> servers();

    ClusterSpec spec();

    /** Free resources on a server given the jobs currently running (ignores any plan). */
    ResourceVector free(int serverIndex);

    /** Jobs that have arrived and not finished, running or waiting, in arrival order. */
    List<JobInfo> activeJobs();

    /** Organisation: teams, quotas, priority classes. */
    Organisation organisation();

    default Server server(int index) {
        return servers().get(index);
    }

    /** Fraction of all GPUs currently held by running jobs. */
    default double gpuBusyFraction() {
        int total = spec().totalCapacity().gpus(), used = 0;
        for (JobInfo j : activeJobs()) if (j.isRunning()) used += j.allocation().gpus();
        return total == 0 ? 0 : (double) used / total;
    }
}
