package org.cloudbus.cloudsim.cosched.policy;

/** When scheduling rounds happen: every {@code roundS} seconds, and/or when jobs arrive or end. */
public interface SchedulingTrigger {

    /** Seconds between rounds, or 0 for no periodic rounds. */
    double roundS();

    /** Run a round as soon as a job arrives. */
    boolean onArrival();

    /** Run a round as soon as a job ends. */
    boolean onJobEnd();
}
