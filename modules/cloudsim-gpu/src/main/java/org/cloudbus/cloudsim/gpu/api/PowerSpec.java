package org.cloudbus.cloudsim.gpu.api;

/**
 * Power inputs of one server. There are no defaults: every number must come from a stated
 * source (spec sheet, SPECpower result, measurement) in the experiment config.
 *
 * <p>Server power at a moment is
 * {@code P = P_cpu(u_cpu) + sum over GPUs of P_gpu(u_gpu) + baseW}, and idle servers stay on.
 *
 * @param cpuModel   {@code "linear"} (uses cpuIdleW and cpuMaxW) or the simple class name of one
 *                   of CloudSim's SPECpower models, e.g. {@code "PowerModelSpecPowerIbmX3550XeonX5675"}
 *                   (cpuIdleW and cpuMaxW are then ignored)
 * @param cpuIdleW   whole-CPU power at 0% utilisation
 * @param cpuMaxW    whole-CPU power at 100%
 * @param gpuIdleW   power of one GPU while held but idle (also while unallocated)
 * @param gpuMaxW    power of one GPU fully busy (V100: 250 W PCIe, 300 W SXM2 rated)
 * @param baseW      everything else on the server (fans, disks, NICs), constant
 */
public record PowerSpec(String cpuModel, double cpuIdleW, double cpuMaxW,
                        double gpuIdleW, double gpuMaxW, double baseW) {

    public PowerSpec {
        if (cpuModel == null || cpuModel.isBlank()) throw new IllegalArgumentException("cpu power model is required");
        if (cpuModel.equals("linear") && !(0 <= cpuIdleW && cpuIdleW <= cpuMaxW))
            throw new IllegalArgumentException("need 0 <= cpuIdleW <= cpuMaxW, got " + cpuIdleW + ", " + cpuMaxW);
        if (!(0 <= gpuIdleW && gpuIdleW <= gpuMaxW))
            throw new IllegalArgumentException("need 0 <= gpuIdleW <= gpuMaxW, got " + gpuIdleW + ", " + gpuMaxW);
        if (baseW < 0) throw new IllegalArgumentException("baseW must be >= 0");
    }

    public static PowerSpec linear(double cpuIdleW, double cpuMaxW, double gpuIdleW, double gpuMaxW, double baseW) {
        return new PowerSpec("linear", cpuIdleW, cpuMaxW, gpuIdleW, gpuMaxW, baseW);
    }
}
