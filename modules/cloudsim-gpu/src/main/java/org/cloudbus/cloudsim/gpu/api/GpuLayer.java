package org.cloudbus.cloudsim.gpu.api;

import org.cloudbus.cloudsim.core.SimEntity;

import java.util.List;

/**
 * Builds the simulated GPU cluster and hands out its {@link PlanExecutor}.
 *
 * <p>Lifecycle: after {@code CloudSim.init(...)}, the framework calls {@link #build(List)} once
 * (this creates the datacenter and its hosts), then creates its broker and calls
 * {@link #bind(SimEntity)}. The broker forwards every event it receives to
 * {@link PlanExecutor#handle} before handling it itself.
 */
public interface GpuLayer {

    /** Creates one host per server shape, in index order, inside a single datacenter. */
    void build(List<ServerShape> servers);

    /** The executor that acts on behalf of {@code broker}; valid once the simulation starts. */
    PlanExecutor bind(SimEntity broker);

    /** Datacenter entity id, for the broker's characteristics request. Valid after {@link #build}. */
    int datacenterId();

    /** State of one server now: what is allocated, utilisation and power. */
    ServerStatus status(int serverIndex);

    /** Number of servers built. */
    int serverCount();
}
