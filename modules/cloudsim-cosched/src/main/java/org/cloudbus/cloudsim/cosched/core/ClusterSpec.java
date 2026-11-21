package org.cloudbus.cloudsim.cosched.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The cluster section of the experiment YAML: a list of server groups and an optional rack count.
 * Servers are numbered group by group, in the order the groups are written.
 */
public record ClusterSpec(
        @JsonProperty("server_groups") List<ServerGroup> serverGroups,
        Integer racks) {

    public ClusterSpec {
        if (serverGroups == null || serverGroups.isEmpty())
            throw new IllegalArgumentException("cluster needs at least one server group");
        Set<String> names = new HashSet<>();
        for (ServerGroup g : serverGroups)
            if (!names.add(g.name())) throw new IllegalArgumentException("duplicate server group name: " + g.name());
        if (racks != null && racks <= 0) throw new IllegalArgumentException("racks must be > 0");
        serverGroups = List.copyOf(serverGroups);
    }

    public ClusterSpec(List<ServerGroup> serverGroups) {
        this(serverGroups, null);
    }

    /** Synergy's default cluster: 16 servers x 8 V100, 24 CPUs, 500 GB, 500 MB/s storage. */
    public static ClusterSpec synergyDefault() {
        return synergy(24);
    }

    /** Synergy's cluster with a different CPU count per server (Fig 12 uses 32 / 40 / 48). */
    public static ClusterSpec synergy(int cpusPerServer) {
        return new ClusterSpec(List.of(new ServerGroup("synergy-v100", 16, 8, "V100", cpusPerServer, 500, 500, 0)));
    }

    /** All servers, expanded from the groups and numbered from 0. */
    public List<Server> servers() {
        List<Server> out = new ArrayList<>();
        for (ServerGroup g : serverGroups)
            for (int i = 0; i < g.count(); i++) out.add(new Server(out.size(), g));
        return Collections.unmodifiableList(out);
    }

    public int serverCount() {
        return serverGroups.stream().mapToInt(ServerGroup::count).sum();
    }

    /** Total resources of the whole cluster (G, C, M in the DRF and Tetris keys). */
    public ResourceVector totalCapacity() {
        ResourceVector t = ResourceVector.ZERO;
        for (ServerGroup g : serverGroups)
            t = t.plus(new ResourceVector(g.gpus() * g.count(), g.cpus() * g.count(), g.memGb() * g.count()));
        return t;
    }

    /** GPU types present, used to check that a profile exists for each. */
    public Set<String> gpuTypes() {
        Set<String> s = new HashSet<>();
        for (ServerGroup g : serverGroups) s.add(g.gpuType());
        return s;
    }
}
