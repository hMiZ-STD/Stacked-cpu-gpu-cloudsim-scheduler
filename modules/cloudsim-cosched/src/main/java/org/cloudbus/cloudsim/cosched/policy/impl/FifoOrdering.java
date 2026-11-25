package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.model.ThroughputModel;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** First come, first served: by arrival time, ties by job id. */
public final class FifoOrdering implements OrderingPolicy {

    @Override
    public List<JobInfo> order(List<JobInfo> jobs, ClusterState state, ThroughputModel throughput) {
        List<JobInfo> l = new ArrayList<>(jobs);
        l.sort(Comparator.comparingDouble(JobInfo::arrivalTime).thenComparingInt(JobInfo::id));
        return l;
    }
}
