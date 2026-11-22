package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;

import java.util.function.Function;

/** Decides which servers a job runs on, reading free capacity from the round plan. */
public interface PlacementPolicy {

    /**
     * An allocation for {@code job} that fits the plan's free capacity, or null if none does.
     * {@code demand} gives the job's total demand on servers of a group (it may differ per group).
     * Must not write to the plan; the caller assigns the result.
     */
    Allocation place(JobInfo job, Function<ServerGroup, ResourceVector> demand, RoundPlan plan);

    /** Largest GPU count this policy can ever place on {@code maxGpusPerServer}-GPU servers. */
    int maxPlaceableGpus(int maxGpusPerServer, int totalGpus);
}
