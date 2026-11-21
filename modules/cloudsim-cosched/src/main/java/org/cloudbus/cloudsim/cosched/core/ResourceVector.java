package org.cloudbus.cloudsim.cosched.core;

/**
 * An amount of the three resources a training job holds: whole GPUs, CPU cores and memory.
 * Immutable. Cores and memory are doubles because Synergy allocates fractional shares.
 */
public record ResourceVector(int gpus, double cpus, double memGb) {

    public static final ResourceVector ZERO = new ResourceVector(0, 0, 0);

    /** Tolerance for floating-point capacity checks. */
    public static final double EPS = 1e-9;

    public ResourceVector {
        if (gpus < 0 || cpus < -EPS || memGb < -EPS)
            throw new IllegalArgumentException("negative resource: " + gpus + " gpus, " + cpus + " cpus, " + memGb + " GB");
    }

    public static ResourceVector of(int gpus, double cpus, double memGb) {
        return new ResourceVector(gpus, cpus, memGb);
    }

    public ResourceVector plus(ResourceVector o) {
        return new ResourceVector(gpus + o.gpus, cpus + o.cpus, memGb + o.memGb);
    }

    /** Subtraction that may go negative; use {@link #isNonNegative()} to check. Returns a raw triple. */
    public Delta minus(ResourceVector o) {
        return new Delta(gpus - o.gpus, cpus - o.cpus, memGb - o.memGb);
    }

    /** True if this amount fits inside {@code capacity} on every dimension. */
    public boolean fitsIn(ResourceVector capacity) {
        return gpus <= capacity.gpus && cpus <= capacity.cpus + EPS && memGb <= capacity.memGb + EPS;
    }

    public boolean isZero() {
        return gpus == 0 && Math.abs(cpus) <= EPS && Math.abs(memGb) <= EPS;
    }

    /** CPU cores per GPU held (0 when no GPUs). */
    public double cpusPerGpu() {
        return gpus == 0 ? 0 : cpus / gpus;
    }

    /** A difference of two vectors; may be negative on any dimension. */
    public record Delta(int gpus, double cpus, double memGb) {
        public boolean isNonNegative() {
            return gpus >= 0 && cpus >= -EPS && memGb >= -EPS;
        }

        /** Clamps tiny negative float noise to zero; throws if genuinely negative. */
        public ResourceVector toVector() {
            if (!isNonNegative()) throw new IllegalStateException("negative remainder: " + this);
            return new ResourceVector(gpus, Math.max(0, cpus), Math.max(0, memGb));
        }
    }
}
