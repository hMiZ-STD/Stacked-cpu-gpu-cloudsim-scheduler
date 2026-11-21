package org.cloudbus.cloudsim.gpu;

/**
 * One physical GPU of a server. GPUs are whole and exclusive: at most one guest holds it.
 * {@code socket} is a hook for NUMA-aware placement (not modelled in v1; always 0).
 */
public final class Gpu {

    private final int index;
    private final String type;
    private final int socket;
    private int ownerGuestId = -1;
    private double busyFraction;

    public Gpu(int index, String type, int socket) {
        this.index = index;
        this.type = type;
        this.socket = socket;
    }

    public int index() {
        return index;
    }

    public String type() {
        return type;
    }

    public int socket() {
        return socket;
    }

    public boolean isFree() {
        return ownerGuestId < 0;
    }

    /** Guest id holding this GPU, or -1. */
    public int ownerGuestId() {
        return ownerGuestId;
    }

    /** Fraction of time computing (0 when free or stalled on input). */
    public double busyFraction() {
        return isFree() ? 0 : busyFraction;
    }

    void assign(int guestId, double busy) {
        if (!isFree()) throw new IllegalStateException("GPU " + index + " already held by guest " + ownerGuestId);
        ownerGuestId = guestId;
        busyFraction = busy;
    }

    void setBusyFraction(double busy) {
        busyFraction = busy;
    }

    void free() {
        ownerGuestId = -1;
        busyFraction = 0;
    }
}
