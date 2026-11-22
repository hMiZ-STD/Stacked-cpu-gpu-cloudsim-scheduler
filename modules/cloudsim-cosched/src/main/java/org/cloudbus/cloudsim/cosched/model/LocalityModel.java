package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.Allocation;

/**
 * Speed factor for where a job's parts run: 1 on one server, {@code penalty} when spread over
 * several. Synergy's published runs use 1 (every active profile has {@code placement_penalty = 1});
 * a real penalty is our own extension, to be calibrated from Philly's measurements.
 */
public final class LocalityModel {

    private final double penalty;

    public LocalityModel(double penalty) {
        if (!(penalty > 0 && penalty <= 1)) throw new IllegalArgumentException("locality penalty must be in (0, 1], got " + penalty);
        this.penalty = penalty;
    }

    public double penalty() {
        return penalty;
    }

    public double factor(Allocation a) {
        return a.isSpread() ? penalty : 1.0;
    }
}
