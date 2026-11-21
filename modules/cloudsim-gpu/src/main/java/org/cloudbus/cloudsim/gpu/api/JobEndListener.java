package org.cloudbus.cloudsim.gpu.api;

/** Called by the GPU layer when every part of a job has finished its work. */
@FunctionalInterface
public interface JobEndListener {

    /** @param finishTime simulated seconds; resources are already released when this is called */
    void jobEnded(int jobId, double finishTime);
}
