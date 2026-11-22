package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.Server;

import java.util.List;

/** The read-only cluster view handed to policies at the start of a round. */
final class StateSnapshot implements ClusterState {

    private final double now;
    private final ClusterSpec spec;
    private final List<Server> servers;
    private final Organisation org;
    private final List<JobInfo> active;
    private final ResourceVector[] free;

    StateSnapshot(double now, ClusterSpec spec, List<Server> servers, Organisation org, List<JobInfo> active) {
        this.now = now;
        this.spec = spec;
        this.servers = servers;
        this.org = org;
        this.active = List.copyOf(active);
        ResourceVector[] used = new ResourceVector[servers.size()];
        java.util.Arrays.fill(used, ResourceVector.ZERO);
        for (JobInfo j : this.active)
            if (j.isRunning()) for (Allocation.Part p : j.allocation().parts())
                used[p.serverIndex()] = used[p.serverIndex()].plus(p.resources());
        free = new ResourceVector[servers.size()];
        for (int i = 0; i < free.length; i++) free[i] = servers.get(i).capacity().minus(used[i]).toVector();
    }

    @Override public double now() { return now; }
    @Override public List<Server> servers() { return servers; }
    @Override public ClusterSpec spec() { return spec; }
    @Override public ResourceVector free(int serverIndex) { return free[serverIndex]; }
    @Override public List<JobInfo> activeJobs() { return active; }
    @Override public Organisation organisation() { return org; }
}
