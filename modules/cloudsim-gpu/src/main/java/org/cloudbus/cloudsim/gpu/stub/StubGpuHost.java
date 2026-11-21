package org.cloudbus.cloudsim.gpu.stub;

import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.List;

/**
 * Stand-in GPU server. CloudSim only tracks one very large PE whose MIPS carry job speed;
 * GPUs, cores and memory are tracked here, and any over-commit is a bug (contract rule 5).
 * The real GPU layer replaces this with proper GPU provisioning and power models.
 */
public class StubGpuHost extends PowerHost {

    /** PE capacity, far above any sum of job speeds, so CloudSim never limits speed. */
    static final double PE_MIPS = 1e15;

    private final ServerShape shape;
    private int usedGpus;
    private double usedCpus, usedMem;

    public StubGpuHost(ServerShape shape) {
        this(shape, List.of(new Pe(new PeProvisionerSimple(PE_MIPS))));
    }

    /** The host and its guest scheduler must share the same PE objects. */
    private StubGpuHost(ServerShape shape, List<Pe> pes) {
        super(shape.index(),
                new RamProvisionerSimple(Integer.MAX_VALUE),
                new BwProvisionerSimple(Long.MAX_VALUE / 4),
                Long.MAX_VALUE / 4,
                pes,
                new VmSchedulerTimeShared(pes),
                new PowerModelLinear(1000, 30));
        this.shape = shape;
    }

    public ServerShape shape() {
        return shape;
    }

    public int usedGpus() {
        return usedGpus;
    }

    public double usedCpus() {
        return usedCpus;
    }

    public double usedMem() {
        return usedMem;
    }

    void reserve(PartSpec p) {
        int g = usedGpus + p.gpus();
        double c = usedCpus + p.cpus(), m = usedMem + p.memGb();
        if (g > shape.gpus() || c > shape.cpus() + 1e-9 || m > shape.memGb() + 1e-9)
            throw new IllegalStateException("server " + shape.index() + " over-committed by " + p
                    + " (used " + usedGpus + " gpus, " + usedCpus + " cpus, " + usedMem + " GB)");
        usedGpus = g;
        usedCpus = c;
        usedMem = m;
    }

    void release(PartSpec p) {
        usedGpus -= p.gpus();
        usedCpus = Math.max(0, usedCpus - p.cpus());
        usedMem = Math.max(0, usedMem - p.memGb());
        if (usedGpus < 0) throw new IllegalStateException("server " + shape.index() + " released more GPUs than held");
    }
}
