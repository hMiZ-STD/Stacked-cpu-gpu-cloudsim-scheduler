package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;

import java.util.List;

/**
 * Shared arithmetic for placing jobs GPU by GPU. A job split over servers gets cores and memory exactly
 * proportional to its GPUs on each server (Synergy paper §4.2; {@code resources/cluster.py} works with a
 * per-GPU demand vector), because its slowest part sets its speed.
 */
public final class Shares {

    private static final double EPS = ResourceVector.EPS;

    private Shares() {
    }

    /** One GPU's share of a total demand. */
    public static ResourceVector perGpu(ResourceVector total) {
        return new ResourceVector(1, total.cpus() / total.gpus(), total.memGb() / total.gpus());
    }

    /** {@code n} GPUs' worth of a per-GPU share. */
    public static ResourceVector times(ResourceVector perGpu, int n) {
        return new ResourceVector(n, perGpu.cpus() * n, perGpu.memGb() * n);
    }

    /** How many GPUs, each with {@code perGpu} cores and memory, fit in {@code free}. */
    public static int fitCount(ResourceVector free, ResourceVector perGpu) {
        long n = free.gpus();
        if (perGpu.cpus() > EPS) n = Math.min(n, (long) Math.floor((free.cpus() + EPS) / perGpu.cpus()));
        if (perGpu.memGb() > EPS) n = Math.min(n, (long) Math.floor((free.memGb() + EPS) / perGpu.memGb()));
        return (int) Math.max(0, n);
    }

    /**
     * The fixed share of an existing allocation: same servers and GPUs, cores and memory at the fixed
     * CPU:GPU ratio of each server.
     */
    public static Allocation fixedShareOf(Allocation a, RoundPlan plan) {
        List<Allocation.Part> parts = a.parts().stream().map(p -> {
            ServerGroup g = plan.state().server(p.serverIndex()).group();
            return new Allocation.Part(p.serverIndex(), g.proportionalShare(p.resources().gpus()));
        }).toList();
        return new Allocation(parts);
    }

    /** The server group of a server index. */
    public static ServerGroup groupOf(RoundPlan plan, int server) {
        Server s = plan.state().server(server);
        return s.group();
    }
}
