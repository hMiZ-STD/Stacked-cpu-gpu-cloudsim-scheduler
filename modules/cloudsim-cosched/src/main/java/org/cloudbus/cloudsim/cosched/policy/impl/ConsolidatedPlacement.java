package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;

import java.util.function.Function;

/**
 * Puts a job on the fullest server it fits on (fewest free GPUs, ties by lowest index), which keeps
 * free GPUs together for large jobs. STRICT locality: a job never spans servers (Philly's guidance;
 * our default). SOFT locality (spreading with a penalty, as Synergy does) is added in Phase 3.
 */
public final class ConsolidatedPlacement implements PlacementPolicy {

    @Override
    public Allocation place(JobInfo job, Function<ServerGroup, ResourceVector> demand, RoundPlan plan) {
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
        return best == null ? null : Allocation.single(best.index(), bestDemand);
    }

    @Override
    public int maxPlaceableGpus(int maxGpusPerServer, int totalGpus) {
        return maxGpusPerServer;
    }
}
