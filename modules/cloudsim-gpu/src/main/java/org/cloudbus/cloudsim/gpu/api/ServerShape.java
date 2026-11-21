package org.cloudbus.cloudsim.gpu.api;

/**
 * One server the GPU layer must build, numbered 0..n-1 in cluster order.
 * Mirrors one entry of a server group in the experiment YAML.
 */
public record ServerShape(
        int index,
        String group,
        int gpus,
        String gpuType,
        double cpus,
        double memGb,
        double storageMbps,
        double netGbps) {

    public ServerShape {
        if (index < 0 || gpus <= 0 || cpus <= 0 || memGb <= 0)
            throw new IllegalArgumentException("invalid server shape: " + this);
    }
}
