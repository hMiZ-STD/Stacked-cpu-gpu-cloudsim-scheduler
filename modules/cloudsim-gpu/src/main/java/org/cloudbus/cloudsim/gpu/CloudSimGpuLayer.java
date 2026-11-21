package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.gpu.api.GpuLayer;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.cloudbus.cloudsim.gpu.api.ServerStatus;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** The GPU layer: one datacenter with one {@link GpuHost} per server shape, run by {@link GpuPlanExecutor}. */
public class CloudSimGpuLayer implements GpuLayer {

    private final List<GpuHost> hosts = new ArrayList<>();
    private PlannedGuestAllocationPolicy policy;
    private Datacenter datacenter;
    private GpuPlanExecutor executor;

    @Override
    public void build(List<ServerShape> servers) {
        if (datacenter != null) throw new IllegalStateException("already built");
        if (servers.isEmpty()) throw new IllegalArgumentException("need at least one server");
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).index() != i) throw new IllegalArgumentException("server shapes must be numbered 0..n-1 in order");
            hosts.add(new GpuHost(servers.get(i)));
        }
        policy = new PlannedGuestAllocationPolicy(hosts);
        DatacenterCharacteristics ch = new DatacenterCharacteristics(
                "x86", "Linux", "GPU-passthrough", hosts, 0.0, 0.0, 0.0, 0.0, 0.0);
        try {
            datacenter = new Datacenter("gpu-dc", ch, policy, new LinkedList<Storage>(), 0);
        } catch (Exception e) {
            throw new IllegalStateException("could not create the datacenter", e);
        }
    }

    @Override
    public PlanExecutor bind(SimEntity broker) {
        if (datacenter == null) throw new IllegalStateException("build() first");
        if (executor != null) throw new IllegalStateException("already bound to a broker");
        executor = new GpuPlanExecutor(broker, datacenter.getId(), hosts, policy);
        return executor;
    }

    @Override
    public int datacenterId() {
        if (datacenter == null) throw new IllegalStateException("build() first");
        return datacenter.getId();
    }

    @Override
    public ServerStatus status(int serverIndex) {
        return hosts.get(serverIndex).status();
    }

    @Override
    public int serverCount() {
        return hosts.size();
    }

    public List<GpuHost> hosts() {
        return List.copyOf(hosts);
    }
}
