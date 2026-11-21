package org.cloudbus.cloudsim.gpu.stub;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.gpu.api.GpuLayer;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;
import org.cloudbus.cloudsim.gpu.api.ServerShape;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** Stand-in GPU layer: one datacenter, one {@link StubGpuHost} per server. */
public class StubGpuLayer implements GpuLayer {

    private final List<StubGpuHost> hosts = new ArrayList<>();
    private PlannedGuestAllocationPolicy policy;
    private Datacenter datacenter;

    @Override
    public void build(List<ServerShape> servers) {
        if (datacenter != null) throw new IllegalStateException("already built");
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).index() != i) throw new IllegalArgumentException("server shapes must be numbered 0..n-1 in order");
            hosts.add(new StubGpuHost(servers.get(i)));
        }
        policy = new PlannedGuestAllocationPolicy(hosts);
        DatacenterCharacteristics ch = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hosts, 0.0, 0.0, 0.0, 0.0, 0.0);
        try {
            datacenter = new Datacenter("gpu-dc", ch, policy, new LinkedList<Storage>(), 0);
        } catch (Exception e) {
            throw new IllegalStateException("could not create datacenter", e);
        }
    }

    @Override
    public PlanExecutor bind(SimEntity broker) {
        if (datacenter == null) throw new IllegalStateException("build() first");
        return new StubPlanExecutor(broker, datacenter.getId(), hosts, policy);
    }

    @Override
    public int datacenterId() {
        if (datacenter == null) throw new IllegalStateException("build() first");
        return datacenter.getId();
    }

    public List<StubGpuHost> hosts() {
        return List.copyOf(hosts);
    }
}
