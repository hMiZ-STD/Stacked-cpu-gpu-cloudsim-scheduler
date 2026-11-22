package org.cloudbus.cloudsim.cosched.workload;

/**
 * Decides which model a job trains. Traces rarely record it, so it is assigned, exactly as
 * Synergy did. Called once per job, in arrival order.
 */
public interface ModelLabeler {

    /** The model name for {@code job}, used to look up its throughput profile. */
    String label(JobRecord job);
}
