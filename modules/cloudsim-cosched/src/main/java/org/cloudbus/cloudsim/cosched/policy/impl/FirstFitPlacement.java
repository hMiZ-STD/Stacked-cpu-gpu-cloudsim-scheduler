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
import java.util.List;
import java.util.function.Function;

/**
 * First fit over free GPUs in index order: Synergy's {@code SYNERGY_RANDOM} placement
 * ({@code resources/cluster.py, allocate_synergy_random / _top_synergy_gpus}), which Synergy's
 * {@code FIFO+tune}, {@code FIFO+fair} and {@code FIFO} use, despite its name not random. Servers are
 * visited in index order; from each, the job takes as many GPUs as still fit with their per-GPU share of
 * cores and memory.
 *
 * <ul>
 *   <li>SOFT locality (Synergy's behaviour, the default): a multi-GPU job may take GPUs from several
 *       servers, each part proportional to its GPUs.</li>
 *   <li>STRICT locality: only the first server that fits the whole job.</li>
 * </ul>
 */
public final class FirstFitPlacement implements PlacementPolicy {

    private final boolean soft;

    public FirstFitPlacement(boolean soft) {
        this.soft = soft;
    }

    @Override
    public Allocation place(JobInfo job, Function<ServerGroup, ResourceVector> demand, RoundPlan plan) {
        int need = job.gpus();
        List<Allocation.Part> parts = new ArrayList<>();
        for (Server s : plan.state().servers()) {
            ResourceVector perGpu = Shares.perGpu(demand.apply(s.group()));
            int fit = Shares.fitCount(plan.free(s.index()), perGpu);
            if (!soft) {
                if (fit >= need) return Allocation.single(s.index(), Shares.times(perGpu, need));
                continue;
            }
            int take = Math.min(fit, need);
            if (take == 0) continue;
            parts.add(new Allocation.Part(s.index(), Shares.times(perGpu, take)));
            need -= take;
            if (need == 0) return new Allocation(parts);
        }
        return null;
    }

    @Override
    public int maxPlaceableGpus(int maxGpusPerServer, int totalGpus) {
        return soft ? totalGpus : maxGpusPerServer;
    }
}
