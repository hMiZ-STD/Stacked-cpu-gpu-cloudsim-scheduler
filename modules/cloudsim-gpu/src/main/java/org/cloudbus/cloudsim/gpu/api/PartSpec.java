package org.cloudbus.cloudsim.gpu.api;

/** What one part of a job holds on one server: whole GPUs, CPU cores, memory. */
public record PartSpec(int serverIndex, int gpus, double cpus, double memGb) {

    public PartSpec {
        if (serverIndex < 0 || gpus <= 0 || cpus < 0 || memGb < 0)
            throw new IllegalArgumentException("invalid part: " + this);
    }
}
