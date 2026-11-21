package org.cloudbus.cloudsim.gpu.api;

import org.cloudbus.cloudsim.core.SimEvent;

import java.util.List;

/**
 * Turns the scheduler's decisions into CloudSim actions. This is the contract between the
 * co-scheduling framework and the GPU layer; any implementation must follow these rules
 * (found while prototyping, Check 1 of the architecture doc):
 *
 * <ol>
 *   <li>Before any resize or removal, credit every running job's progress at its old speed.</li>
 *   <li>A resize is deallocate, then allocate; allocating twice leaks host capacity.</li>
 *   <li>After a resize, wake the datacenter so finish times are recomputed.</li>
 *   <li>Placement is exactly what the command says: a full host refuses the guest and nothing
 *       falls back to another host.</li>
 *   <li>A refused creation or resize is a bug: throw {@link IllegalStateException} (the run stops).
 *       The broker has already checked the plan against capacity.</li>
 *   <li>The executor never ends the simulation; the broker ends the run from its own last event.</li>
 * </ol>
 *
 * <p>Within one {@link #apply} call the executor processes all {@link JobCommand.Stop}s first,
 * then {@link JobCommand.Resize}s, then {@link JobCommand.Start}s, so capacity freed in a round
 * can be reused in the same round. A job spread over servers has one guest per part and its
 * parts progress in lock-step at the job's rate.
 */
public interface PlanExecutor {

    /** Applies one round's commands at the current simulation time. */
    void apply(List<JobCommand> commands);

    /** Work left for a job (credited up to now); for a stopped job, what it had when stopped. */
    double remainingWork(int jobId);

    /**
     * Offers a CloudSim event that arrived at the broker. Returns true if it belonged to the
     * GPU layer (guest-creation acks, cloudlet returns) and was handled.
     */
    boolean handle(SimEvent ev);

    void setJobEndListener(JobEndListener listener);
}
