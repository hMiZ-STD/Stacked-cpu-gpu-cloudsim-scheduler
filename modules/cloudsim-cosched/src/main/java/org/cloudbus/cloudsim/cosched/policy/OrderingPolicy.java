package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;

import java.util.List;

/** Decides which job gets resources first in a round. */
public interface OrderingPolicy {

    /** {@code jobs} sorted so the job to serve first comes first. Must not modify the input. */
    List<JobInfo> order(List<JobInfo> jobs, ClusterState state);
}
