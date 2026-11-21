package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.gpu.api.PartSpec;

/**
 * The guest of one job part on one server: its GPUs, cores and memory ({@link PartSpec}),
 * isolated from other tenants (GPU passthrough). CloudSim MIPS, RAM and bandwidth are nominal
 * (1 unit each); the real resources are reserved on the {@link GpuHost}.
 */
public class GpuVm extends Vm {

    private final int jobId;
    private PartSpec spec;
    private double gpuBusyFraction;

    public GpuVm(int id, int userId, int jobId, PartSpec spec, double rate, double gpuBusyFraction) {
        super(id, userId, 1, 1, 1, 1, 0, "GPU-passthrough", new GpuCloudletScheduler(rate));
        this.jobId = jobId;
        this.spec = spec;
        this.gpuBusyFraction = gpuBusyFraction;
    }

    public int jobId() {
        return jobId;
    }

    public PartSpec spec() {
        return spec;
    }

    public double gpuBusyFraction() {
        return gpuBusyFraction;
    }

    public GpuCloudletScheduler scheduler() {
        return (GpuCloudletScheduler) getCloudletScheduler();
    }

    void setSpec(PartSpec spec) {
        this.spec = spec;
    }

    void setGpuBusyFraction(double f) {
        this.gpuBusyFraction = f;
    }
}
