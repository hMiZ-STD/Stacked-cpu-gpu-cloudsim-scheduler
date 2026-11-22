package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;

/**
 * Decides how much a job asks for on servers of a group: its GPUs and, the point of this module,
 * how many CPU cores and how much memory.
 */
public interface AllocationPolicy {

    /** Total demand (all GPUs of the job) if placed on servers of {@code group}. */
    ResourceVector demand(JobInfo job, ServerGroup group, ThroughputModel throughput);
}
