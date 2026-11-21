package org.cloudbus.cloudsim.cosched.core;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A group of identical servers, as written in the experiment YAML:
 * <pre>{ name: legacy-v100, count: 16, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 500, net_gbps: 40 }</pre>
 */
public record ServerGroup(
        String name,
        int count,
        int gpus,
        @JsonProperty("gpu_type") String gpuType,
        double cpus,
        @JsonProperty("mem_gb") double memGb,
        @JsonProperty("storage_mbps") double storageMbps,
        @JsonProperty("net_gbps") double netGbps) {

    public ServerGroup {
        require(name != null && !name.isBlank(), "server group needs a name");
        require(count > 0, "server group '" + name + "': count must be > 0");
        require(gpus > 0, "server group '" + name + "': gpus must be > 0");
        require(gpuType != null && !gpuType.isBlank(), "server group '" + name + "': gpu_type is required");
        require(cpus > 0, "server group '" + name + "': cpus must be > 0");
        require(memGb > 0, "server group '" + name + "': mem_gb must be > 0");
        require(storageMbps >= 0, "server group '" + name + "': storage_mbps must be >= 0");
        require(netGbps >= 0, "server group '" + name + "': net_gbps must be >= 0");
    }

    /** Resources of one server in this group. */
    public ResourceVector capacity() {
        return new ResourceVector(gpus, cpus, memGb);
    }

    /** The GPU-proportional ("fixed m:n") share of one GPU: cores and memory per GPU. */
    public ResourceVector proportionalShare(int jobGpus) {
        return new ResourceVector(jobGpus, cpus / gpus * jobGpus, memGb / gpus * jobGpus);
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException(msg);
    }
}
