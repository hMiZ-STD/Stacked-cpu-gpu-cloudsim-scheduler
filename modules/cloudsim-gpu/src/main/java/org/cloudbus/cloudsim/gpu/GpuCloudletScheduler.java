package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.List;

/**
 * Advances training work at a set rate (iterations per second), ignoring CloudSim's MIPS shares.
 * Speed is decided by the scheduler (throughput x locality), so progress is tracked directly in
 * iterations as a double; no rounding to integer MI.
 *
 * <p>One {@link TrainingCloudlet} per guest. A rate change must credit progress at the old rate
 * first ({@link #setRate}); this is contract rule 1, enforced here.
 */
public class GpuCloudletScheduler extends CloudletScheduler {

    /** A cloudlet within this many seconds of done counts as done (float noise at exact finish events). */
    static final double FINISH_SLACK_S = 1e-6;

    private double rate;

    public GpuCloudletScheduler(double rate) {
        if (!(rate > 0)) throw new IllegalArgumentException("rate must be > 0");
        this.rate = rate;
    }

    public double rate() {
        return rate;
    }

    /** Credits progress up to {@code now} at the old rate, then switches to {@code newRate}. */
    public void setRate(double now, double newRate) {
        if (!(newRate > 0)) throw new IllegalArgumentException("rate must be > 0");
        credit(now);
        rate = newRate;
    }

    /** Work left on this guest's cloudlet (0 once finished; full work if not yet submitted). */
    public double remainingWork() {
        for (Cloudlet c : getCloudletExecList()) return ((TrainingCloudlet) c).remainingWork();
        return 0;
    }

    /** Predicted finish time (absolute), or +inf if nothing runs. */
    public double predictedFinish(double now) {
        for (Cloudlet c : getCloudletExecList()) return now + ((TrainingCloudlet) c).remainingWork() / rate;
        return Double.POSITIVE_INFINITY;
    }

    /** Advances progress to {@code now}. Idempotent for the same time. */
    public void credit(double now) {
        double dt = now - getPreviousTime();
        if (dt > 0) for (Cloudlet c : getCloudletExecList()) ((TrainingCloudlet) c).advance(rate * dt);
        if (now > getPreviousTime()) setPreviousTime(now);
    }

    @Override
    public double updateCloudletsProcessing(double currentTime, List<Double> mipsShare) {
        setCurrentMipsShare(mipsShare);
        credit(currentTime);
        List<Cloudlet> exec = List.copyOf(this.<Cloudlet>getCloudletExecList());
        for (Cloudlet c : exec) {
            TrainingCloudlet tc = (TrainingCloudlet) c;
            if (tc.remainingWork() <= rate * FINISH_SLACK_S) {
                tc.markDone();
                getCloudletExecList().remove(tc);
                cloudletFinish(tc);
            }
        }
        if (getCloudletExecList().isEmpty()) return 0.0;
        double next = predictedFinish(currentTime);
        return Math.max(next, currentTime + CloudSim.getMinTimeBetweenEvents());
    }

    @Override
    public double cloudletSubmit(Cloudlet cl, double fileTransferTime) {
        if (!(cl instanceof TrainingCloudlet tc)) throw new IllegalArgumentException("GpuCloudletScheduler runs TrainingCloudlets only");
        if (!getCloudletExecList().isEmpty()) throw new IllegalStateException("one training cloudlet per guest");
        cl.updateStatus(Cloudlet.CloudletStatus.INEXEC);
        getCloudletExecList().add(cl);
        setPreviousTime(CloudSim.clock());           // progress starts now, not at the last host update
        return tc.remainingWork() / rate;            // duration until finish
    }

    @Override
    public double cloudletResume(int clId) {
        throw new UnsupportedOperationException("training jobs are stopped and restarted by the PlanExecutor, not paused");
    }

    @Override
    public double getEstimatedFinishTime(Cloudlet cl, double time) {
        return time + ((TrainingCloudlet) cl).remainingWork() / rate;
    }

    @Override
    public double getTotalUtilizationOfCpu(double time) {
        return getCloudletExecList().isEmpty() ? 0 : 1;
    }

    // MIPS are not used for training progress; report the rate as nominal capacity.
    @Override
    public double getTotalCurrentAvailableMipsForCloudlet(Cloudlet rcl, List<Double> mipsShare) {
        return rate;
    }

    @Override
    public double getTotalCurrentRequestedMipsForCloudlet(Cloudlet cl, double time) {
        return rate;
    }

    @Override
    public double getTotalCurrentAllocatedMipsForCloudlet(Cloudlet cl, double time) {
        return rate;
    }
}
