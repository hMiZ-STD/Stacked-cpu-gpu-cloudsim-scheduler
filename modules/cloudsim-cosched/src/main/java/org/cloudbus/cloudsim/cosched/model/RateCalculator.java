package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;

/**
 * The one place a job's speed is computed:
 * <pre>rate = speedup(model, cores, memory, GPU type) x locality(placement) x interference / iteration time</pre>
 * in iterations per second, which is how Synergy's simulator advances a job
 * ({@code jobs/job.py}: iteration time divided by placement penalty, then by the speedup).
 */
public final class RateCalculator {

    /** A job's speed and how busy its GPUs are. */
    public record Speed(double rate, double gpuBusyFraction) {
    }

    private final ThroughputModel throughput;
    private final LocalityModel locality;
    private final InterferenceModel interference;
    private final ClusterSpec cluster;

    public RateCalculator(ThroughputModel throughput, LocalityModel locality, InterferenceModel interference, ClusterSpec cluster) {
        this.throughput = throughput;
        this.locality = locality;
        this.interference = interference;
        this.cluster = cluster;
    }

    public ThroughputModel throughput() {
        return throughput;
    }

    public LocalityModel locality() {
        return locality;
    }

    /**
     * Speed of {@code job} holding {@code alloc}. All parts of a job must be on servers of one group
     * (one GPU type); mixing types in one job is not modelled.
     */
    public Speed speed(JobInfo job, Allocation alloc, double iterationTimeS) {
        ServerGroup g = groupOf(alloc);
        double s = throughput.speedup(job, alloc.total(), g);
        double rate = s * locality.factor(alloc) * interference.factor(job, alloc) / iterationTimeS;
        return new Speed(rate, throughput.gpuBusyFraction(job, alloc.total(), g));
    }

    /**
     * Whole iterations a job advances in one round of {@code roundS} seconds, as Synergy's simulator computes
     * it ({@code jobs/job.py, allocate}): iteration time, divided by the placement penalty if spread, divided by
     * the speedup, then {@code floor(round / iteration time)}. Same operation order, so the same floating-point result.
     */
    public long iterationsPerRound(JobInfo job, Allocation alloc, double iterationTimeS, double roundS) {
        ServerGroup g = groupOf(alloc);
        double t = iterationTimeS;
        if (alloc.isSpread()) t = t / locality.penalty();
        t = t / interference.factor(job, alloc);
        t = t / throughput.speedup(job, alloc.total(), g);
        return (long) Math.floor(roundS / t);
    }

    /** The server group of an allocation; rejects allocations that span groups. */
    public ServerGroup groupOf(Allocation alloc) {
        ServerGroup g = null;
        for (Allocation.Part p : alloc.parts()) {
            ServerGroup pg = cluster.servers().get(p.serverIndex()).group();
            if (g == null) g = pg;
            else if (!g.name().equals(pg.name()))
                throw new IllegalArgumentException("allocation spans server groups " + g.name() + " and " + pg.name());
        }
        return g;
    }
}
