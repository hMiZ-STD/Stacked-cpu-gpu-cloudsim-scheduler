package org.cloudbus.cloudsim.cosched.workload;

import java.nio.file.Path;
import java.util.List;

/**
 * Makes a job list from a seed, instead of reading a fixed job CSV. Different seeds give different
 * workloads of the same kind, which is what lets a comparison show how much a ranking depends on
 * luck. The same seed always gives the same jobs; every candidate in a comparison runs on the same
 * jobs for a given seed (common random numbers), so differences between candidates are paired.
 */
public interface WorkloadGenerator {

    /**
     * @param seed    the run's seed
     * @param baseDir folder the experiment YAML is in, for resolving relative file parameters
     * @return jobs (unlabelled or labelled), any order; {@link TraceWorkload} sorts and labels them
     */
    List<JobRecord> generate(long seed, Path baseDir);
}
