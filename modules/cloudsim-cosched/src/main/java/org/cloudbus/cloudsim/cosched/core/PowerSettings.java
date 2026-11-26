package org.cloudbus.cloudsim.cosched.core;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Power inputs of one server in a server group, as written in the experiment YAML:
 * <pre>power: { cpu_model: linear, cpu_idle_w: 90, cpu_max_w: 280, gpu_idle_w: 40, gpu_max_w: 250, base_w: 150,
 *          source: "CPU: SPECpower ...; GPU: NVIDIA V100 PCIe datasheet ..." }</pre>
 *
 * <p>There are no defaults: every number must be written in the config, and {@code source} must say
 * where the numbers come from (spec sheet, SPECpower result, measurement, or "placeholder"). The source
 * is printed in every decision report, so a placeholder can never pass for a measured value.
 *
 * @param cpuModel {@code linear} (uses cpu_idle_w and cpu_max_w) or the simple class name of one of
 *                 CloudSim's SPECpower models, e.g. {@code PowerModelSpecPowerIbmX3550XeonX5675}
 *                 (cpu_idle_w and cpu_max_w must then be left out)
 */
public record PowerSettings(
        @JsonProperty("cpu_model") String cpuModel,
        @JsonProperty("cpu_idle_w") Double cpuIdleW,
        @JsonProperty("cpu_max_w") Double cpuMaxW,
        @JsonProperty("gpu_idle_w") Double gpuIdleW,
        @JsonProperty("gpu_max_w") Double gpuMaxW,
        @JsonProperty("base_w") Double baseW,
        String source) {

    public PowerSettings {
        String at = "power settings: ";
        if (cpuModel == null || cpuModel.isBlank()) throw new IllegalArgumentException(at + "cpu_model is required (linear or a CloudSim PowerModelSpecPower* class)");
        if (cpuModel.equals("linear")) {
            if (cpuIdleW == null || cpuMaxW == null)
                throw new IllegalArgumentException(at + "cpu_model linear needs cpu_idle_w and cpu_max_w");
            if (!(0 <= cpuIdleW && cpuIdleW <= cpuMaxW))
                throw new IllegalArgumentException(at + "need 0 <= cpu_idle_w <= cpu_max_w, got " + cpuIdleW + ", " + cpuMaxW);
        } else if (cpuIdleW != null || cpuMaxW != null) {
            throw new IllegalArgumentException(at + "cpu_idle_w and cpu_max_w are only used with cpu_model linear; "
                    + cpuModel + " has its own measured table");
        }
        if (gpuIdleW == null || gpuMaxW == null) throw new IllegalArgumentException(at + "gpu_idle_w and gpu_max_w are required");
        if (!(0 <= gpuIdleW && gpuIdleW <= gpuMaxW))
            throw new IllegalArgumentException(at + "need 0 <= gpu_idle_w <= gpu_max_w, got " + gpuIdleW + ", " + gpuMaxW);
        if (baseW == null || baseW < 0) throw new IllegalArgumentException(at + "base_w is required and must be >= 0");
        if (source == null || source.isBlank())
            throw new IllegalArgumentException(at + "source is required: say where these numbers come from (or write \"placeholder\")");
    }

    /** CPU idle power, 0 when a SPECpower table is used (the table has its own). */
    public double cpuIdleOrZero() {
        return cpuIdleW == null ? 0 : cpuIdleW;
    }

    public double cpuMaxOrZero() {
        return cpuMaxW == null ? 0 : cpuMaxW;
    }
}
