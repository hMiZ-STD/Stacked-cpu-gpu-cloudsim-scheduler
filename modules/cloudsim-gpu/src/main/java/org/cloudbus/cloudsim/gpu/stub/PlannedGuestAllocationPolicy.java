package org.cloudbus.cloudsim.gpu.stub;

import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Do as told" placement (contract rule 4): each guest goes to the host the plan names, or is
 * refused. CloudSim's default policy put 15 of 20 guests off plan in Check 1; this one puts 0.
 */
public class PlannedGuestAllocationPolicy extends VmAllocationPolicySimple {

    private final Map<Integer, Integer> planned = new HashMap<>();

    public PlannedGuestAllocationPolicy(List<? extends HostEntity> hosts) {
        super(hosts);
    }

    void plan(int guestId, int hostIndex) {
        planned.put(guestId, hostIndex);
    }

    void forget(int guestId) {
        planned.remove(guestId);
    }

    @Override
    public HostEntity findHostForGuest(GuestEntity guest) {
        Integer idx = planned.get(guest.getId());
        if (idx == null) return null;
        HostEntity h = getHostList().get(idx);
        return h.isSuitableForGuest(guest) ? h : null;
    }
}
