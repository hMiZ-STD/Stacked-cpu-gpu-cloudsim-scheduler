package org.cloudbus.cloudsim.cosched.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a job runs and what it holds on each server. A job on one server has one part;
 * a job spread over servers (SOFT locality) has one part per server, moving in lock-step.
 */
public record Allocation(List<Part> parts) {

    /** What a job holds on one server. */
    public record Part(int serverIndex, ResourceVector resources) {
        public Part {
            if (serverIndex < 0) throw new IllegalArgumentException("server index must be >= 0");
            if (resources.gpus() == 0) throw new IllegalArgumentException("a part must hold at least one GPU");
        }
    }

    public Allocation {
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("allocation needs at least one part");
        List<Integer> seen = new ArrayList<>();
        for (Part p : parts) {
            if (seen.contains(p.serverIndex()))
                throw new IllegalArgumentException("two parts on server " + p.serverIndex() + "; merge them");
            seen.add(p.serverIndex());
        }
        parts = List.copyOf(parts);
    }

    public static Allocation single(int serverIndex, ResourceVector r) {
        return new Allocation(List.of(new Part(serverIndex, r)));
    }

    public ResourceVector total() {
        ResourceVector t = ResourceVector.ZERO;
        for (Part p : parts) t = t.plus(p.resources());
        return t;
    }

    public int gpus() {
        return total().gpus();
    }

    /** True when the job spans more than one server. */
    public boolean isSpread() {
        return parts.size() > 1;
    }

    /** What this allocation holds on {@code serverIndex}, or zero. */
    public ResourceVector on(int serverIndex) {
        for (Part p : parts) if (p.serverIndex() == serverIndex) return p.resources();
        return ResourceVector.ZERO;
    }

    /** Same servers and same GPU count per server (only cores or memory may differ). */
    public boolean samePlacement(Allocation o) {
        if (o == null || parts.size() != o.parts.size()) return false;
        for (Part p : parts) if (o.on(p.serverIndex()).gpus() != p.resources().gpus()) return false;
        return true;
    }
}
