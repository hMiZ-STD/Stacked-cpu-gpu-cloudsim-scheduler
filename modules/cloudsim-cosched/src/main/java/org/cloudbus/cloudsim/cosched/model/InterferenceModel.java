package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;

/**
 * The named seam where a measured slowdown from jobs on neighbouring GPUs would go. Not modelled in
 * v1, as in Synergy and the simulators it compares against; Philly measured up to 12.1% with two
 * co-located jobs, which every decision report states as an assumption.
 */
@FunctionalInterface
public interface InterferenceModel {

    /** No interference: jobs on separate GPUs do not slow each other. */
    InterferenceModel NONE = (job, alloc) -> 1.0;

    /** Speed factor in (0, 1] for {@code job} holding {@code alloc}. */
    double factor(JobInfo job, Allocation alloc);
}
