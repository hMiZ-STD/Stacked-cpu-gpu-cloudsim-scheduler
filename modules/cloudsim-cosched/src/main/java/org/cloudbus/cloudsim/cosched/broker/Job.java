package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.workload.JobRecord;

import java.util.function.IntToDoubleFunction;

/**
 * A job as the broker tracks it. Policies see it read-only, through {@link JobInfo}; only the broker
 * changes its allocation. Remaining work comes from the GPU layer, which owns progress.
 */
public final class Job implements JobInfo {

    private final JobRecord rec;
    private final double totalWork;
    private final double iterTimeS;
    private final IntToDoubleFunction remainingFromGpuLayer;
    private final java.util.function.DoubleSupplier clock;

    private Allocation allocation;
    private Allocation lastAllocation;
    private boolean everStarted;
    private double firstStart = Double.NaN, finish = Double.NaN;
    private double attainedGpuS, since;
    private int starts;

    /**
     * @param totalWork iterations
     * @param iterTimeS seconds per iteration at the fixed share
     */
    Job(JobRecord rec, double totalWork, double iterTimeS, IntToDoubleFunction remainingFromGpuLayer,
        java.util.function.DoubleSupplier clock) {
        if (rec.model() == null) throw new IllegalArgumentException("job " + rec.jobId() + " has no model label");
        this.rec = rec;
        this.totalWork = totalWork;
        this.iterTimeS = iterTimeS;
        this.remainingFromGpuLayer = remainingFromGpuLayer;
        this.clock = clock;
    }

    @Override public int id() { return rec.jobId(); }
    @Override public double arrivalTime() { return rec.arrivalS(); }
    @Override public int gpus() { return rec.gpus(); }
    @Override public String model() { return rec.model(); }
    @Override public String team() { return rec.team() == null ? Organisation.DEFAULT : rec.team(); }
    @Override public String priority() { return rec.priority() == null ? Organisation.DEFAULT : rec.priority(); }
    @Override public double totalWork() { return totalWork; }
    @Override public double firstStartTime() { return firstStart; }
    @Override public Allocation allocation() { return allocation; }

    @Override
    public double remainingWork() {
        if (!Double.isNaN(finish)) return 0;
        return everStarted ? remainingFromGpuLayer.applyAsDouble(id()) : totalWork;
    }

    @Override
    public double remainingTimeAtProportionalShare() {
        return remainingWork() * iterTimeS;
    }

    @Override
    public double attainedServiceGpuSeconds() {
        return allocation == null ? attainedGpuS : attainedGpuS + allocation.gpus() * (clock.getAsDouble() - since);
    }

    public double iterTimeS() { return iterTimeS; }
    public double finishTime() { return finish; }
    public boolean everStarted() { return everStarted; }
    /** Times the job was started or restarted. */
    public int starts() { return starts; }
    /** The allocation it held last (also after it ended). */
    public Allocation lastAllocation() { return lastAllocation; }
    public JobRecord record() { return rec; }

    void setAllocation(Allocation a, double now) {
        accrue(now);
        if (a != null && allocation == null) {
            starts++;
            if (!everStarted) {
                everStarted = true;
                firstStart = now;
            }
        }
        allocation = a;
        if (a != null) lastAllocation = a;
    }

    void finish(double now) {
        accrue(now);
        allocation = null;
        finish = now;
    }

    private void accrue(double now) {
        if (allocation != null) attainedGpuS += allocation.gpus() * (now - since);
        since = now;
    }
}
