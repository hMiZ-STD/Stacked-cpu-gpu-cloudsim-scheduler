package org.cloudbus.cloudsim.cosched.workload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Poisson arrivals at a given load, as Synergy's simulator generates them
 * ({@code helpers/utils.py, poisson_next_arrival_time}): each inter-arrival time is
 * {@code ceil(-ln(1 - U) / jobs_per_hour * 3600)} whole seconds, and the first job arrives one
 * inter-arrival time after 0.
 *
 * <p>What each job is comes from one of two sources:
 * <ul>
 *   <li>{@link Source#SYNTHETIC}: Synergy's synthetic recipe ({@code helpers/utils.py}). Run time at
 *       the fixed share is {@code 60 * 10^U(1.5, 3)} s with probability 0.8, else {@code 60 * 10^U(3, 4)} s
 *       ({@code get_gavel_like_iter}, the Gavel-style mix of short and long jobs); GPUs are 1, or
 *       Synergy's multi-GPU mix 1 (70%), 2 (10%), 4 (15%), 8 (5%) ({@code get_job_gpu_demand}).
 *       Models are left to the labeller.</li>
 *   <li>{@link Source#RESAMPLE}: each job copies the GPUs, work and model of a row drawn uniformly
 *       (with replacement) from a job CSV, e.g. a real trace. Only the arrivals are new.</li>
 * </ul>
 * The paper's own runs draw durations from the Philly trace with Poisson arrivals; resampling the
 * {@code synergy-replica} job list gives that distribution.
 */
public final class PoissonWorkload implements WorkloadGenerator {

    public enum Source { SYNTHETIC, RESAMPLE }

    public enum GpuMix { SINGLE, SYNERGY_MIX }

    private final double jobsPerHour;
    private final int jobs;
    private final Source source;
    private final GpuMix gpuMix;
    private final String resampleCsv;

    public PoissonWorkload(double jobsPerHour, int jobs, Source source, GpuMix gpuMix, String resampleCsv) {
        if (!(jobsPerHour > 0)) throw new IllegalArgumentException("jobs_per_hour must be > 0");
        if (jobs <= 0) throw new IllegalArgumentException("jobs must be > 0");
        if (source == Source.RESAMPLE && (resampleCsv == null || resampleCsv.isBlank()))
            throw new IllegalArgumentException("source resample needs resample_csv");
        this.jobsPerHour = jobsPerHour;
        this.jobs = jobs;
        this.source = source;
        this.gpuMix = gpuMix;
        this.resampleCsv = resampleCsv;
    }

    public static PoissonWorkload synthetic(double jobsPerHour, int jobs, GpuMix mix) {
        return new PoissonWorkload(jobsPerHour, jobs, Source.SYNTHETIC, mix, null);
    }

    public static PoissonWorkload resample(double jobsPerHour, int jobs, String csv) {
        return new PoissonWorkload(jobsPerHour, jobs, Source.RESAMPLE, null, csv);
    }

    @Override
    public List<JobRecord> generate(long seed, Path baseDir) {
        Random rng = new Random(seed);
        List<JobRecord> pool = null;
        if (source == Source.RESAMPLE) {
            Path p = Path.of(resampleCsv);
            pool = JobCsvReader.read(p.isAbsolute() ? p : baseDir.resolve(p).normalize());
            if (pool.isEmpty()) throw new IllegalArgumentException("resample_csv " + resampleCsv + " has no jobs");
        }
        List<JobRecord> out = new ArrayList<>(jobs);
        double t = 0;
        for (int id = 0; id < jobs; id++) {
            t += nextInterArrival(rng);
            int line = id + 2;
            if (pool == null) {
                out.add(new JobRecord(id, t, gpus(rng), null, null, gavelDurationS(rng), null, null, null, line));
            } else {
                JobRecord r = pool.get(rng.nextInt(pool.size()));
                out.add(new JobRecord(id, t, r.gpus(), r.iterations(), r.iterTimeS(), r.durationS(), r.model(),
                        r.team(), r.priority(), line));
            }
        }
        return out;
    }

    /** Synergy's poisson_next_arrival_time, in whole seconds. */
    double nextInterArrival(Random rng) {
        double hours = -Math.log(1.0 - rng.nextDouble()) / jobsPerHour;
        return Math.ceil(hours * 3600);
    }

    /** Synergy's get_gavel_like_iter: run time in seconds at the fixed share. */
    static double gavelDurationS(Random rng) {
        boolean longJob = rng.nextDouble() >= 0.8;
        double exp = longJob ? 3 + rng.nextDouble() : 1.5 + 1.5 * rng.nextDouble();
        return 60 * Math.pow(10, exp);
    }

    /** Synergy's get_job_gpu_demand, or always 1. */
    int gpus(Random rng) {
        if (gpuMix == GpuMix.SINGLE) return 1;
        double u = rng.nextDouble();
        if (u >= 0.95) return 8;
        if (u >= 0.8) return 4;
        if (u >= 0.7) return 2;
        return 1;
    }
}
