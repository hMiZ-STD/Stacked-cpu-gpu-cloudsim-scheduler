package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.cosched.core.Allocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Turns a round plan into changes: what to start, resize and stop. Unchanged jobs get no change. */
public final class PlanDiff {

    public enum Kind { START, RESIZE, STOP }

    /** One change; {@code to} is null for STOP. */
    public record Change(int jobId, Kind kind, Allocation to) {
    }

    private PlanDiff() {
    }

    /**
     * @param running jobs running now and their allocations
     * @param planned the plan's allocations
     * @return changes in job-id order
     */
    public static List<Change> diff(Map<Integer, Allocation> running, Map<Integer, Allocation> planned) {
        TreeSet<Integer> ids = new TreeSet<>(running.keySet());
        ids.addAll(planned.keySet());
        List<Change> out = new ArrayList<>();
        for (int id : ids) {
            Allocation now = running.get(id), next = planned.get(id);
            if (now == null) out.add(new Change(id, Kind.START, next));
            else if (next == null) out.add(new Change(id, Kind.STOP, null));
            else if (!now.equals(next)) out.add(new Change(id, Kind.RESIZE, next));
        }
        return out;
    }
}
