package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;

/**
 * Allocation and placement decided together, for policies that may change other jobs' allocations
 * while placing one (Synergy-Tune shrinks co-located jobs to make room). Used instead of an
 * {@link AllocationPolicy}; it places through the candidate's {@link PlacementPolicy}.
 */
public interface JointAllocationPlacement {

    /**
     * Returns the job's allocation, or null if it is skipped this round. May revise other jobs' entries
     * in {@code plan} (through {@link RoundPlan#assign}); must not write the job's own entry.
     */
    Allocation place(JobInfo job, RoundPlan plan, PlacementPolicy placement, ThroughputModel throughput);
}
