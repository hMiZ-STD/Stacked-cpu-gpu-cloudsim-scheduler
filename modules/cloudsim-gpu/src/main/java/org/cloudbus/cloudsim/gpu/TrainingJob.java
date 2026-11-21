package org.cloudbus.cloudsim.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A running training job as the GPU layer sees it: 1..k guest parts (one per server) that move in
 * lock-step at one rate. Each part's cloudlet holds the job's full remaining work, so all parts
 * finish at the same instant. Created on Start, discarded on Stop or when the job ends.
 */
public final class TrainingJob {

    private final int jobId;
    private final List<GpuVm> parts = new ArrayList<>();
    private final List<TrainingCloudlet> cloudlets = new ArrayList<>();
    private final List<Boolean> done = new ArrayList<>();
    private double rate;

    TrainingJob(int jobId, double rate) {
        this.jobId = jobId;
        this.rate = rate;
    }

    public int jobId() {
        return jobId;
    }

    public List<GpuVm> parts() {
        return Collections.unmodifiableList(parts);
    }

    public double rate() {
        return rate;
    }

    void addPart(GpuVm vm, TrainingCloudlet cl) {
        parts.add(vm);
        cloudlets.add(cl);
        done.add(false);
    }

    void setRate(double rate) {
        this.rate = rate;
    }

    /** Work left. Parts are in lock-step, so any unfinished part is representative. */
    public double remainingWork() {
        for (int i = 0; i < parts.size(); i++) if (!done.get(i)) return cloudlets.get(i).remainingWork();
        return 0;
    }

    int indexOf(GpuVm vm) {
        return parts.indexOf(vm);
    }

    TrainingCloudlet cloudlet(int i) {
        return cloudlets.get(i);
    }

    void markDone(int i) {
        done.set(i, true);
    }

    boolean isDone(int i) {
        return done.get(i);
    }

    boolean allDone() {
        return !done.contains(false);
    }
}
