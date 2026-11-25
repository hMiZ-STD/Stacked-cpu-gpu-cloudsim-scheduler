package org.cloudbus.cloudsim.cosched.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;

/**
 * One measured throughput profile: how fast a model trains on {@code gpuCount} GPUs of one type
 * for each (cores per GPU, GB of memory per GPU) point. Read from the profile JSON written by
 * {@code tools/convert_synergy_profiles.py} (format in {@code docs/formats.md}); unknown fields are
 * ignored.
 *
 * <p>{@code throughput[i][j]} is relative speed at {@code cpus[i]} cores and {@code memGbPerGpu[j]}
 * GB per GPU. The table's own normalisation point is not trusted: {@link ProfileTableModel}
 * normalises to the fixed share of the server the job runs on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Profile(
        String model,
        @JsonProperty("gpu_type") String gpuType,
        @JsonProperty("gpu_count") int gpuCount,
        @JsonProperty("iter_time_s") double iterTimeS,
        @JsonProperty("ideal_cpus_per_gpu") double idealCpusPerGpu,
        @JsonProperty("ideal_mem_gb_per_gpu") double idealMemGbPerGpu,
        @JsonProperty("ideal_storage_speed_per_gpu") Double idealStorageSpeedPerGpu,
        @JsonProperty("ideal_speedup") double idealSpeedup,
        @JsonProperty("placement_penalty") double placementPenalty,
        double[] cpus,
        @JsonProperty("mem_gb_per_gpu") double[] memGbPerGpu,
        double[][] throughput,
        @JsonProperty("used_by_synergy") Boolean usedBySynergy,
        String source) {

    public Profile {
        String id = model + "/" + gpuType + "/" + gpuCount + (source == null ? "" : " (" + source + ")");
        require(model != null && !model.isBlank(), id, "model is required");
        require(gpuType != null && !gpuType.isBlank(), id, "gpu_type is required");
        require(gpuCount > 0, id, "gpu_count must be > 0");
        require(iterTimeS > 0, id, "iter_time_s must be > 0");
        require(idealCpusPerGpu > 0 && idealMemGbPerGpu > 0, id, "ideal demand must be > 0");
        require(idealSpeedup > 0, id, "ideal_speedup must be > 0");
        require(placementPenalty > 0 && placementPenalty <= 1, id, "placement_penalty must be in (0, 1]");
        require(cpus != null && cpus.length > 0 && increasing(cpus), id, "cpus must be a strictly increasing list");
        require(memGbPerGpu != null && memGbPerGpu.length > 0 && increasing(memGbPerGpu), id,
                "mem_gb_per_gpu must be a strictly increasing list");
        require(throughput != null && throughput.length == cpus.length, id, "throughput needs one row per cpus entry");
        int lastMeasured = -1;
        for (int i = 0; i < throughput.length; i++) {
            double[] row = throughput[i];
            require(row.length == memGbPerGpu.length, id, "every throughput row needs one value per mem_gb_per_gpu entry");
            boolean allPositive = true, allZero = true;
            for (double v : row) {
                require(v >= 0, id, "throughput values must be >= 0");
                allPositive &= v > 0;
                allZero &= v == 0;
            }
            // Synergy's multi-GPU files mark unmeasured high-CPU rows with 0; only trailing all-zero rows are allowed.
            if (allPositive) {
                require(lastMeasured == i - 1, id, "a measured row follows an unmeasured (all-zero) row at " + cpus[i] + " cores");
                lastMeasured = i;
            } else require(allZero, id, "row at " + cpus[i] + " cores mixes zero and non-zero values");
        }
        require(lastMeasured >= 0, id, "no measured throughput row");
        cpus = cpus.clone();
        memGbPerGpu = memGbPerGpu.clone();
        double[][] t = new double[throughput.length][];
        for (int i = 0; i < t.length; i++) t[i] = throughput[i].clone();
        throughput = t;
    }

    /** Lookup key of this profile. */
    public Key key() {
        return new Key(model, gpuType, gpuCount);
    }

    public record Key(String model, String gpuType, int gpuCount) {
    }

    /** Number of measured CPU rows (rows after these are all zero: not measured). */
    public int measuredRows() {
        int n = 0;
        while (n < throughput.length && throughput[n][0] > 0) n++;
        return n;
    }

    /** Largest CPU point measured, cores per GPU. Beyond it throughput is held flat. */
    public double maxMeasuredCpus() {
        return cpus[measuredRows() - 1];
    }

    private static boolean increasing(double[] a) {
        for (int i = 1; i < a.length; i++) if (!(a[i] > a[i - 1])) return false;
        return true;
    }

    private static void require(boolean ok, String id, String msg) {
        if (!ok) throw new IllegalArgumentException("profile " + id + ": " + msg);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Profile p && key().equals(p.key()) && Arrays.equals(cpus, p.cpus)
                && Arrays.equals(memGbPerGpu, p.memGbPerGpu) && Arrays.deepEquals(throughput, p.throughput);
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return "Profile[" + model + ", " + gpuType + ", " + gpuCount + " GPU]";
    }
}
