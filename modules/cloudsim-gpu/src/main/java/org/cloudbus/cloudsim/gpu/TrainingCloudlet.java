package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModelFull;

/**
 * The work of one job part, in iterations. Progress is a double advanced by
 * {@link GpuCloudletScheduler} at the job's rate; CloudSim's integer MI length is only nominal.
 */
public class TrainingCloudlet extends Cloudlet {

    private final int jobId;
    private final double work;
    private double remaining;

    public TrainingCloudlet(int cloudletId, int jobId, double work, int userId, int guestId) {
        super(cloudletId, Math.max(1, (long) Math.ceil(work)), 1, 0, 0,
                new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
        if (!(work > 0)) throw new IllegalArgumentException("work must be > 0");
        this.jobId = jobId;
        this.work = work;
        this.remaining = work;
        setUserId(userId);
        setGuestId(guestId);
    }

    public int jobId() {
        return jobId;
    }

    /** Work this part started with (iterations). */
    public double work() {
        return work;
    }

    public double remainingWork() {
        return remaining;
    }

    void advance(double done) {
        remaining = Math.max(0, remaining - done);
    }

    void markDone() {
        remaining = 0;
    }
}
