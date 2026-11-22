package org.cloudbus.cloudsim.cosched.policy.impl;

import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;

/** The two scheduling triggers. */
public final class Triggers {

    private Triggers() {
    }

    /** A round every {@code roundS} seconds (Synergy: 300 s, {@code round_duration=300}). */
    public static SchedulingTrigger periodic(double roundS) {
        if (!(roundS > 0)) throw new IllegalArgumentException("round_s must be > 0");
        return new SchedulingTrigger() {
            public double roundS() { return roundS; }
            public boolean onArrival() { return false; }
            public boolean onJobEnd() { return false; }
        };
    }

    /** A round on every arrival and every job end, and no periodic rounds. */
    public static SchedulingTrigger eventDriven() {
        return new SchedulingTrigger() {
            public double roundS() { return 0; }
            public boolean onArrival() { return true; }
            public boolean onJobEnd() { return true; }
        };
    }
}
