package org.cloudbus.cloudsim.cosched.core;

/** One concrete server: its index in the cluster (0..n-1) and the group it came from. */
public record Server(int index, ServerGroup group) {

    public ResourceVector capacity() {
        return group.capacity();
    }

    public String gpuType() {
        return group.gpuType();
    }
}
