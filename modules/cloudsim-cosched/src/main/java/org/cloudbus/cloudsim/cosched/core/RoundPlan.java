package org.cloudbus.cloudsim.cosched.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The draft allocations of one scheduling round. Policies write here and may revise
 * earlier entries in the same round (Synergy-Tune shrinks co-located jobs while placing one).
 * The broker checks the finished plan and hands it to the GPU layer in one go.
 *
 * <p>Every entry is capacity-checked when written, so a plan is always feasible.
 * Running jobs left out of the plan are stopped (preempted) when it is applied.
 */
public final class RoundPlan {

    /** How the plan starts. */
    public enum Start {
        /** Empty: every running job must be re-allocated or it is preempted (Synergy's round-based mode). */
        EMPTY,
        /** Running jobs keep their current allocations; the round only adds waiting jobs. */
        CARRY_RUNNING
    }

    private final ClusterState state;
    private final Map<Integer, JobInfo> jobs = new HashMap<>();
    private final LinkedHashMap<Integer, Allocation> entries = new LinkedHashMap<>();
    private final ResourceVector[] used;

    public RoundPlan(ClusterState state, Start start) {
        this.state = state;
        this.used = new ResourceVector[state.servers().size()];
        java.util.Arrays.fill(used, ResourceVector.ZERO);
        for (JobInfo j : state.activeJobs()) {
            jobs.put(j.id(), j);
            if (start == Start.CARRY_RUNNING && j.isRunning()) {
                if (!assign(j.id(), j.allocation()))
                    throw new IllegalStateException("running job " + j.id() + " does not fit its own server; cluster state is corrupt");
            }
        }
    }

    public ClusterState state() {
        return state;
    }

    /** Free resources on a server after the entries written so far. */
    public ResourceVector free(int serverIndex) {
        return state.server(serverIndex).capacity().minus(used[serverIndex]).toVector();
    }

    /**
     * Writes or replaces the allocation of {@code jobId}. Returns false, and changes nothing,
     * if any part does not fit the server's free capacity (counting the job's own previous
     * entry as free) or if the GPU count is not the job's full gang.
     */
    public boolean assign(int jobId, Allocation alloc) {
        JobInfo job = requireJob(jobId);
        if (alloc.gpus() != job.gpus()) return false;
        Allocation previous = entries.get(jobId);
        for (Allocation.Part p : alloc.parts()) {
            if (p.serverIndex() >= used.length) return false;
            ResourceVector freeHere = state.server(p.serverIndex()).capacity()
                    .minus(used[p.serverIndex()]).toVector();
            if (previous != null) freeHere = freeHere.plus(previous.on(p.serverIndex()));
            if (!p.resources().fitsIn(freeHere)) return false;
        }
        if (previous != null) release(previous);
        for (Allocation.Part p : alloc.parts()) used[p.serverIndex()] = used[p.serverIndex()].plus(p.resources());
        entries.put(jobId, alloc);
        return true;
    }

    /** Removes a job's entry; a running job without an entry is preempted when the plan applies. */
    public void remove(int jobId) {
        Allocation previous = entries.remove(jobId);
        if (previous != null) release(previous);
    }

    public Allocation get(int jobId) {
        return entries.get(jobId);
    }

    /** The active job with this id (read-only view). */
    public JobInfo job(int jobId) {
        return requireJob(jobId);
    }

    /** Free GPUs over the whole cluster after the entries written so far. */
    public int freeGpus() {
        int n = 0;
        for (int s = 0; s < used.length; s++) n += free(s).gpus();
        return n;
    }

    public boolean contains(int jobId) {
        return entries.containsKey(jobId);
    }

    /** Planned allocations in the order they were first written. */
    public Map<Integer, Allocation> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** Jobs that hold something on {@code serverIndex} in this plan. */
    public List<Integer> jobsOn(int serverIndex) {
        return entries.entrySet().stream()
                .filter(e -> e.getValue().on(serverIndex).gpus() > 0)
                .map(Map.Entry::getKey).toList();
    }

    private void release(Allocation a) {
        for (Allocation.Part p : a.parts())
            used[p.serverIndex()] = used[p.serverIndex()].minus(p.resources()).toVector();
    }

    private JobInfo requireJob(int jobId) {
        JobInfo j = jobs.get(jobId);
        if (j == null) throw new IllegalArgumentException("job " + jobId + " is not active in this round");
        return j;
    }
}
