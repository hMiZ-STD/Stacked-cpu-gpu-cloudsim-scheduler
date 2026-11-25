package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.JointAllocationPlacement;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.Shares;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Synergy-Tune, following Synergy's simulator exactly ({@code resources/cluster.py}, {@code _tune},
 * {@code _get_underutilized_servers}, {@code _reallocate_peer}, {@code _make_fair_share}):
 *
 * <ol>
 *   <li>Skip the job if the cluster has fewer free GPUs than it needs (Synergy only calls Tune then).</li>
 *   <li>Place it at its ideal demand.</li>
 *   <li>Else, if its demand exceeds the fixed share in any dimension, drop it to the fixed share and retry.</li>
 *   <li>Else pick servers with enough free GPUs and, on each, shrink co-located jobs to the fixed share until
 *       the job's share fits. Peers are tried on fewer servers first, then most cores per GPU, then most
 *       memory per GPU.</li>
 *   <li>Place it again. If it still does not fit, Synergy's simulator exits; here the run stops.</li>
 * </ol>
 * Placement is the candidate's placement policy (Synergy's {@code FIFO+tune} uses first fit).
 */
public final class SynergyTune implements JointAllocationPlacement {

    private static final double EPS = ResourceVector.EPS;

    /**
     * Per-server job order within the current round. Synergy sorts each server's job list in place
     * ({@code job_list.sort(...)}), so later sorts in the same round start from the earlier order; keeping it
     * reproduces Synergy's order for peers with equal keys.
     */
    private final Map<Integer, List<Integer>> serverOrder = new LinkedHashMap<>();
    private RoundPlan currentPlan;

    @Override
    public Allocation place(JobInfo job, RoundPlan plan, PlacementPolicy placement, ThroughputModel tm) {
        if (plan != currentPlan) {
            currentPlan = plan;
            serverOrder.clear();
        }
        if (plan.freeGpus() < job.gpus()) return null;

        Function<ServerGroup, ResourceVector> demand = g -> tm.idealDemand(job, g);
        Allocation a = placement.place(job, demand, plan);
        if (a != null) return a;

        ServerGroup g0 = plan.state().spec().serverGroups().get(0);
        if (aboveFixedShare(tm.idealDemand(job, g0), g0)) {
            demand = g -> g.proportionalShare(job.gpus());
            a = placement.place(job, demand, plan);
            if (a != null) return a;
        }

        for (var e : underutilizedServers(job.gpus(), plan).entrySet())
            shrinkPeers(plan, e.getKey(), e.getValue(), job.gpus(), demand, orderOn(plan, e.getKey()));

        a = placement.place(job, demand, plan);
        if (a == null)
            throw new IllegalStateException("Synergy-Tune could not place job " + job.id() + " (" + job.gpus()
                    + " GPUs) even after shrinking co-located jobs; Synergy's simulator exits here (cluster.py _tune)");
        return a;
    }

    /** Synergy's _make_fair_share test: does the demand exceed the fixed share in any dimension? */
    static boolean aboveFixedShare(ResourceVector d, ServerGroup g) {
        ResourceVector fair = g.proportionalShare(d.gpus());
        return d.cpus() > fair.cpus() + EPS || d.memGb() > fair.memGb() + EPS;
    }

    /**
     * Servers to make room on, with the GPUs to use there. More than 1 GPU: the first server (by index) with
     * enough free GPUs. Otherwise, or if none: servers by fewest free GPUs (ties by index) until enough GPUs.
     */
    static Map<Integer, Integer> underutilizedServers(int gpus, RoundPlan plan) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (gpus > 1)
            for (Server s : plan.state().servers())
                if (plan.free(s.index()).gpus() >= gpus) {
                    out.put(s.index(), gpus);
                    return out;
                }
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (Server s : plan.state().servers()) {
            int f = plan.free(s.index()).gpus();
            if (f > 0) heap.add(new int[]{f, s.index()});
        }
        int need = gpus;
        while (need > 0 && !heap.isEmpty()) {
            int[] top = heap.poll();
            int take = Math.min(top[0], need);
            out.put(top[1], take);
            need -= take;
        }
        return out;
    }

    /** The server's job list as Synergy keeps it this round: earlier (sorted) order, then newly placed jobs. */
    private List<Integer> orderOn(RoundPlan plan, int server) {
        List<Integer> l = serverOrder.computeIfAbsent(server, k -> new ArrayList<>());
        for (int id : plan.jobsOn(server)) if (!l.contains(id)) l.add(id);
        l.removeIf(id -> !plan.jobsOn(server).contains(id));
        return l;
    }

    /** Synergy's _reallocate_peer plus the re-allocation of the chosen peers at the fixed share. */
    private static void shrinkPeers(RoundPlan plan, int server, int gpusHere, int jobGpus,
                                    Function<ServerGroup, ResourceVector> demand, List<Integer> peers) {
        ServerGroup g = plan.state().server(server).group();
        ResourceVector d = demand.apply(g);
        double ratio = (double) gpusHere / jobGpus;
        ResourceVector free = plan.free(server);
        double[] need = {
                Math.max(0, gpusHere - free.gpus()),
                Math.max(0, d.cpus() * ratio - free.cpus()),
                Math.max(0, d.memGb() * ratio - free.memGb())};
        if (allZero(need)) return;

        // in place and stable, as Synergy
        peers.sort(Comparator.<Integer>comparingInt(id -> plan.get(id).parts().size())
                .thenComparingDouble(id -> -plan.get(id).total().cpusPerGpu())
                .thenComparingDouble(id -> -plan.get(id).total().memGb() / plan.get(id).gpus()));

        List<Integer> chosen = new ArrayList<>();
        for (int id : peers) {
            Allocation pa = plan.get(id);
            int here = pa.on(server).gpus();
            ResourceVector fairHere = g.proportionalShare(here);
            double share = (double) here / pa.gpus();
            ResourceVector t = pa.total();
            double[] excess = {
                    Math.max(0, t.gpus() * share - fairHere.gpus()),
                    Math.max(0, t.cpus() * share - fairHere.cpus()),
                    Math.max(0, t.memGb() * share - fairHere.memGb())};
            double[] diff = new double[3];
            for (int k = 0; k < 3; k++) diff[k] = Math.max(0, need[k] - excess[k]);
            if (allZero(diff)) {
                chosen.add(id);
                break;
            } else if (allPositive(diff)) {
                continue;
            } else {
                chosen.add(id);
                need = diff;
            }
        }
        for (int id : chosen) {
            Allocation current = plan.get(id);
            JobInfo peer = plan.job(id);
            // Synergy switches the peer to the fixed share only if it is above it in some dimension.
            boolean above = false;
            for (Allocation.Part p : current.parts())
                above |= aboveFixedShare(p.resources(), plan.state().server(p.serverIndex()).group());
            if (!above) continue;
            if (!plan.assign(peer.id(), Shares.fixedShareOf(current, plan)))
                throw new IllegalStateException("shrinking job " + id + " to the fixed share did not fit; plan is inconsistent");
        }
    }

    private static boolean allZero(double[] v) {
        for (double x : v) if (x > EPS) return false;
        return true;
    }

    private static boolean allPositive(double[] v) {
        for (double x : v) if (x <= EPS) return false;
        return true;
    }
}
