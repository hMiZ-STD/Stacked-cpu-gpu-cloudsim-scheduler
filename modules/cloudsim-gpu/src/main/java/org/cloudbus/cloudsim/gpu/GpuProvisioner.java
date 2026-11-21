package org.cloudbus.cloudsim.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gang allocation of whole GPUs on one server: a guest gets all the GPUs it asks for at once,
 * or none (Philly's gang scheduling). Picks the lowest-numbered free GPUs, so runs are repeatable.
 */
public final class GpuProvisioner {

    private final List<Gpu> gpus;
    private final Map<Integer, List<Gpu>> byGuest = new HashMap<>();

    public GpuProvisioner(int count, String type) {
        List<Gpu> l = new ArrayList<>(count);
        for (int i = 0; i < count; i++) l.add(new Gpu(i, type, 0));
        this.gpus = Collections.unmodifiableList(l);
    }

    public List<Gpu> gpus() {
        return gpus;
    }

    public int freeCount() {
        int n = 0;
        for (Gpu g : gpus) if (g.isFree()) n++;
        return n;
    }

    /** Allocates {@code n} GPUs to {@code guestId}; returns them, or an empty list if fewer are free. */
    public List<Gpu> allocate(int guestId, int n, double busyFraction) {
        if (n <= 0) throw new IllegalArgumentException("must allocate at least one GPU");
        if (byGuest.containsKey(guestId)) throw new IllegalStateException("guest " + guestId + " already holds GPUs");
        if (freeCount() < n) return List.of();
        List<Gpu> got = new ArrayList<>(n);
        for (Gpu g : gpus) {
            if (got.size() == n) break;
            if (g.isFree()) {
                g.assign(guestId, busyFraction);
                got.add(g);
            }
        }
        byGuest.put(guestId, got);
        return Collections.unmodifiableList(got);
    }

    public void setBusyFraction(int guestId, double busyFraction) {
        for (Gpu g : heldBy(guestId)) g.setBusyFraction(busyFraction);
    }

    public void release(int guestId) {
        List<Gpu> held = byGuest.remove(guestId);
        if (held == null) throw new IllegalStateException("guest " + guestId + " holds no GPUs");
        for (Gpu g : held) g.free();
    }

    public List<Gpu> heldBy(int guestId) {
        return byGuest.getOrDefault(guestId, List.of());
    }

    /** Mean busy fraction over every GPU of the server. */
    public double utilisation() {
        double s = 0;
        for (Gpu g : gpus) s += g.busyFraction();
        return gpus.isEmpty() ? 0 : s / gpus.size();
    }
}
