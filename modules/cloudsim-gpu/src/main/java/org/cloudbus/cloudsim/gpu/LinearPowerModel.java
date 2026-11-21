package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.power.models.PowerModel;

/**
 * {@code P(u) = idle + (max - idle) * u}, with the device on at u = 0.
 *
 * <p>CloudSim's own {@code PowerModelLinear} returns 0 W at u = 0 (it assumes an idle host
 * is switched off). The architecture keeps idle servers on, so this model is used instead.
 * Known limit, stated in every report: a straight line to rated maximum overestimates GPU power,
 * since measured GPUs run below rated power and their power can saturate.
 */
public final class LinearPowerModel implements PowerModel {

    private final double idleW, maxW;

    public LinearPowerModel(double idleW, double maxW) {
        if (!(0 <= idleW && idleW <= maxW)) throw new IllegalArgumentException("need 0 <= idle <= max");
        this.idleW = idleW;
        this.maxW = maxW;
    }

    @Override
    public double getPower(double u) {
        if (u < -1e-9 || u > 1 + 1e-9) throw new IllegalArgumentException("utilisation must be in [0, 1], got " + u);
        return idleW + (maxW - idleW) * Math.min(1, Math.max(0, u));
    }
}
