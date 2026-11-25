package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.Shares;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Puts a job on the fullest server it fits on (fewest free GPUs, ties by lowest index), which keeps free
 * GPUs together for large jobs.
 *
 * <ul>
 *   <li>STRICT locality (our default, Philly's guidance): never spans servers.</li>
 *   <li>SOFT locality: Synergy's {@code SYNERGY_PLACEMENT} ({@code resources/cluster.py,
 *       allocate_synergy_placement}). A multi-GPU job first tries the fullest server that fits it whole;
 *       otherwise, and always for a 1-GPU job, it takes one GPU at a time from the server with the fewest
 *       free GPUs where one more GPU's share still fits. Parts are proportional to their GPUs.</li>
 * </ul>
 */
public final class ConsolidatedPlacement implements PlacementPolicy {

    private final boolean soft;

    public ConsolidatedPlacement() {
        this(false);
    }

    public ConsolidatedPlacement(boolean soft) {
        this.soft = soft;
    }

    @Override
    public Allocation place(JobInfo job, Function<ServerGroup, ResourceVector> demand, RoundPlan plan) {
        if (!soft || job.gpus() > 1) {
            Server best = null;
            int bestFreeGpus = Integer.MAX_VALUE;
            ResourceVector bestDemand = null;
            for (Server s : plan.state().servers()) {
                ResourceVector d = demand.apply(s.group());
                ResourceVector free = plan.free(s.index());
                if (!d.fitsIn(free)) continue;
                if (free.gpus() < bestFreeGpus) {
                    best = s;
                    bestFreeGpus = free.gpus();
                    bestDemand = d;
                }
            }
            if (best != null) return Allocation.single(best.index(), bestDemand);
            if (!soft) return null;
        }
        return spreadFromFullest(job, demand, plan);
    }

    /** One GPU at a time from a min-heap of (free GPUs, server index), as Synergy's _top_synergy_gpus_placement. */
    private static Allocation spreadFromFullest(JobInfo job, Function<ServerGroup, ResourceVector> demand, RoundPlan plan) {
        int n = plan.state().servers().size();
        int[] freeGpus = new int[n];
        ResourceVector[] free = new ResourceVector[n];
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (Server s : plan.state().servers()) {
            free[s.index()] = plan.free(s.index());
            freeGpus[s.index()] = free[s.index()].gpus();
            if (freeGpus[s.index()] > 0) heap.add(new int[]{freeGpus[s.index()], s.index()});
        }
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        int need = job.gpus();
        while (need > 0 && !heap.isEmpty()) {
            int s = heap.poll()[1];
            ResourceVector perGpu = Shares.perGpu(demand.apply(plan.state().server(s).group()));
            if (Shares.fitCount(free[s], perGpu) < 1) continue;                 // does not fit: server dropped
            free[s] = free[s].minus(Shares.times(perGpu, 1)).toVector();
            taken.merge(s, 1, Integer::sum);
            need--;
            if (free[s].gpus() > 0) heap.add(new int[]{free[s].gpus(), s});
        }
        if (need > 0) return null;
        List<Allocation.Part> parts = new ArrayList<>();
        for (var e : taken.entrySet()) {
            ResourceVector perGpu = Shares.perGpu(demand.apply(plan.state().server(e.getKey()).group()));
            parts.add(new Allocation.Part(e.getKey(), Shares.times(perGpu, e.getValue())));
        }
        return new Allocation(parts);
    }

    @Override
    public int maxPlaceableGpus(int maxGpusPerServer, int totalGpus) {
        return soft ? totalGpus : maxGpusPerServer;
    }
}
