package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PowerSpec;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.cloudbus.cloudsim.gpu.api.ServerStatus;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A GPU server built from one {@link ServerShape}: whole GPUs of one type (gang-allocated by a
 * {@link GpuProvisioner}), CPU cores and memory (fractional, as Synergy allocates them), and a
 * power model for CPU, GPUs and the rest of the server.
 *
 * <p>Resources are reserved when the executor applies a command ({@link #reserve}), not when
 * CloudSim later processes the guest-creation event, so a round's stops, resizes and starts see
 * consistent capacity. CloudSim then creates only guests that hold a reservation here.
 * CloudSim's PE/MIPS, RAM and bandwidth accounting is nominal and never limits a job.
 */
public class GpuHost extends PowerHost {

    /** Nominal PE capacity; every guest asks for 1 MIPS. */
    static final double PE_MIPS = 1e9;

    private final ServerShape shape;
    private final GpuProvisioner gpus;
    private final PowerModel cpuPower, gpuPower;
    private final double baseW;
    private final Map<Integer, PartSpec> reserved = new HashMap<>();
    private double usedCpus, usedMem;

    public GpuHost(ServerShape shape) {
        this(shape, List.of(new Pe(new PeProvisionerSimple(PE_MIPS))));
    }

    private GpuHost(ServerShape shape, List<Pe> pes) {
        super(shape.index(),
                new RamProvisionerSimple(Integer.MAX_VALUE),
                new BwProvisionerSimple(Long.MAX_VALUE / 4),
                Long.MAX_VALUE / 4,
                pes,
                new VmSchedulerTimeShared(pes),
                shape.power() == null ? new LinearPowerModel(0, 0) : cpuModel(shape.power()));
        this.shape = shape;
        this.gpus = new GpuProvisioner(shape.gpus(), shape.gpuType());
        PowerSpec p = shape.power();
        this.cpuPower = p == null ? null : getPowerModel();
        this.gpuPower = p == null ? null : new LinearPowerModel(p.gpuIdleW(), p.gpuMaxW());
        this.baseW = p == null ? 0 : p.baseW();
    }

    /** Builds the CPU power model: our linear model, or one of CloudSim's SPECpower tables by name. */
    static PowerModel cpuModel(PowerSpec p) {
        if (p.cpuModel().equals("linear")) return new LinearPowerModel(p.cpuIdleW(), p.cpuMaxW());
        String cls = "org.cloudbus.cloudsim.power.models." + p.cpuModel();
        try {
            return (PowerModel) Class.forName(cls).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("unknown CPU power model '" + p.cpuModel() + "' (expected 'linear' or a "
                    + "CloudSim PowerModelSpecPower* class name)", e);
        }
    }

    public ServerShape shape() {
        return shape;
    }

    public GpuProvisioner gpuProvisioner() {
        return gpus;
    }

    // ---- reservations ---------------------------------------------------------------------

    /** Reserves GPUs (gang), cores and memory for a guest part; throws if they do not fit (rule 5). */
    void reserve(GpuVm vm) {
        PartSpec s = vm.spec();
        if (reserved.containsKey(vm.getId())) throw new IllegalStateException("guest " + vm.getId() + " already reserved");
        if (s.serverIndex() != shape.index()) throw new IllegalStateException("part for server " + s.serverIndex() + " sent to " + shape.index());
        checkFits(s.cpus(), s.memGb(), vm.getId());
        if (gpus.allocate(vm.getId(), s.gpus(), vm.gpuBusyFraction()).isEmpty())
            throw new IllegalStateException(over(vm.getId(), s.gpus() + " GPUs (" + gpus.freeCount() + " free)"));
        usedCpus += s.cpus();
        usedMem += s.memGb();
        reserved.put(vm.getId(), s);
    }

    /**
     * First half of an in-place resize (rule 2: deallocate, then allocate): frees the guest's cores
     * and memory but keeps its GPUs. The executor frees every resized guest of a round before
     * re-reserving any, so a grow and a shrink on one server never conflict on order.
     */
    void releaseCoresAndMemory(GpuVm vm) {
        PartSpec cur = reserved.get(vm.getId());
        if (cur == null) throw new IllegalStateException("guest " + vm.getId() + " is not reserved here");
        usedCpus = clamp(usedCpus - cur.cpus());
        usedMem = clamp(usedMem - cur.memGb());
        reserved.put(vm.getId(), new PartSpec(cur.serverIndex(), cur.gpus(), 0, 0));
    }

    /** Second half of an in-place resize: reserves the new cores and memory (same GPUs). */
    void reserveCoresAndMemory(GpuVm vm, PartSpec next, double busyFraction) {
        PartSpec cur = reserved.get(vm.getId());
        if (cur == null) throw new IllegalStateException("guest " + vm.getId() + " is not reserved here");
        if (cur.cpus() != 0 || cur.memGb() != 0) throw new IllegalStateException("release cores and memory first");
        if (next.gpus() != cur.gpus()) throw new IllegalStateException("an in-place resize cannot change GPUs");
        checkFits(next.cpus(), next.memGb(), vm.getId());
        usedCpus += next.cpus();
        usedMem += next.memGb();
        reserved.put(vm.getId(), next);
        gpus.setBusyFraction(vm.getId(), busyFraction);
    }

    void release(GpuVm vm) {
        PartSpec cur = reserved.remove(vm.getId());
        if (cur == null) throw new IllegalStateException("guest " + vm.getId() + " is not reserved here");
        gpus.release(vm.getId());
        usedCpus = clamp(usedCpus - cur.cpus());
        usedMem = clamp(usedMem - cur.memGb());
    }

    public boolean isReserved(int guestId) {
        return reserved.containsKey(guestId);
    }

    @Override
    public boolean isSuitableForGuest(GuestEntity guest) {
        return guest instanceof GpuVm && isReserved(guest.getId()) && super.isSuitableForGuest(guest);
    }

    @Override
    public boolean guestCreate(GuestEntity guest) {
        if (!(guest instanceof GpuVm) || !isReserved(guest.getId())) return false;   // rule 4: only planned guests
        return super.guestCreate(guest);
    }

    // ---- utilisation and power -------------------------------------------------------------

    public int usedGpus() {
        return shape.gpus() - gpus.freeCount();
    }

    public double usedCpus() {
        return usedCpus;
    }

    public double usedMemGb() {
        return usedMem;
    }

    /** Allocated cores over total cores (assumption: allocated cores are fully busy). */
    public double cpuUtilisation() {
        return Math.min(1, usedCpus / shape.cpus());
    }

    public double gpuUtilisation() {
        return gpus.utilisation();
    }

    /** Current server power in watts, or NaN when the shape has no power spec. */
    public double powerW() {
        if (cpuPower == null) return Double.NaN;
        double p = cpuPower.getPower(cpuUtilisation()) + baseW;
        for (Gpu g : gpus.gpus()) p += gpuPower.getPower(g.busyFraction());
        return p;
    }

    /** CloudSim's power hook returns the same server power (CPU + GPUs + base). */
    @Override
    public double getPower() {
        return powerW();
    }

    public ServerStatus status() {
        return new ServerStatus(shape.index(), usedGpus(), usedCpus, usedMem, cpuUtilisation(), gpuUtilisation(), powerW());
    }

    private void checkFits(double cpus, double mem, int guestId) {
        if (usedCpus + cpus > shape.cpus() + 1e-9) throw new IllegalStateException(over(guestId, cpus + " cores"));
        if (usedMem + mem > shape.memGb() + 1e-9) throw new IllegalStateException(over(guestId, mem + " GB"));
    }

    private String over(int guestId, String what) {
        return "server " + shape.index() + " over-committed: guest " + guestId + " needs " + what
                + " (used " + usedGpus() + "/" + shape.gpus() + " GPUs, " + usedCpus + "/" + shape.cpus()
                + " cores, " + usedMem + "/" + shape.memGb() + " GB)";
    }

    private static double clamp(double v) {
        return Math.abs(v) < 1e-9 ? 0 : v;
    }
}
