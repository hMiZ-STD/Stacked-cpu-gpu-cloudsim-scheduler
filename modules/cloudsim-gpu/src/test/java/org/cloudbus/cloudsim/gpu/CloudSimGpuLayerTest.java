package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.gpu.api.GpuLayer;
import org.cloudbus.cloudsim.gpu.api.JobCommand.Resize;
import org.cloudbus.cloudsim.gpu.api.JobCommand.Start;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PowerSpec;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.cloudbus.cloudsim.gpu.api.ServerStatus;
import org.cloudbus.cloudsim.gpu.contract.PlanExecutorContractTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The real GPU layer: the shared contract, plus what only it models (GPUs, exact progress, power). */
class CloudSimGpuLayerTest extends PlanExecutorContractTest {

    @Override
    protected GpuLayer newLayer() {
        return new CloudSimGpuLayer();
    }

    static final PowerSpec POWER = PowerSpec.linear(100, 200, 50, 300, 100);

    private CloudSimGpuLayer powered() {
        return (CloudSimGpuLayer) cluster(List.of(new ServerShape(0, "g", 8, "V100", 24, 500, 500, 40, POWER)));
    }

    @Test
    void progressIsExactWithoutMipsRounding() {
        GpuLayer layer = cluster(1);
        double[] rem = new double[1];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 1, 3), 1.0 / 3))
                .at(100, e -> rem[0] = e.remainingWork(1));
        run(d);
        assertEquals(1000 - 100.0 / 3, rem[0], 1e-9);
        assertEquals(3000, d.finished.get(1), TOL);
    }

    @Test
    void growAndShrinkOnOneServerInOneRoundInAnyOrder() {
        GpuLayer layer = cluster(1);
        double[] cpus = new double[1];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 4, 18), 1.0), new Start(2, 1000, on(0, 4, 6), 1.0))
                // grow job 2 to 18 cores, listed before shrinking job 1 to 6: only fits if all shrinks apply first
                .apply(100, new Resize(2, on(0, 4, 18), 2.0), new Resize(1, on(0, 4, 6), 0.5))
                .at(100, e -> cpus[0] = layer.status(0).usedCpus());
        run(d);
        assertEquals(24, cpus[0], 1e-9);
        assertEquals(100 + 900 / 2.0, d.finished.get(2), TOL);
        assertEquals(100 + 900 / 0.5, d.finished.get(1), TOL);
    }

    @Test
    void gpusAreGangAllocatedLowestFirstAndFreedOnEnd() {
        CloudSimGpuLayer layer = (CloudSimGpuLayer) cluster(1);
        GpuProvisioner gp = layer.hosts().get(0).gpuProvisioner();
        int[][] owners = new int[1][];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 100, on(0, 3, 9), 1.0), new Start(2, 500, on(0, 2, 6), 1.0))
                .at(50, e -> owners[0] = gp.gpus().stream().mapToInt(Gpu::ownerGuestId).toArray());
        run(d);
        int a = owners[0][0], b = owners[0][3];
        assertTrue(a >= 0 && b >= 0 && a != b);
        assertArrayEquals(new int[]{a, a, a, b, b, -1, -1, -1}, owners[0]);
        assertEquals(8, gp.freeCount());
    }

    @Test
    void hostOnlyAcceptsReservedGuests() {
        CloudSimGpuLayer layer = (CloudSimGpuLayer) cluster(1);
        GpuHost host = layer.hosts().get(0);
        GpuVm stray = new GpuVm(999, 0, 1, new PartSpec(0, 1, 1, 1), 1.0, 1.0);
        assertFalse(host.isSuitableForGuest(stray));
        assertFalse(host.guestCreate(stray));
    }

    @Test
    void serverPowerIsCpuPlusGpusPlusBase() {
        CloudSimGpuLayer layer = powered();
        ServerStatus[] at = new ServerStatus[3];
        Driver d = new Driver(layer)
                .at(0, e -> at[0] = layer.status(0))
                // 4 GPUs at 42% busy (ResNet-18 at the fixed share: 1/2.4), 12 of 24 cores
                .apply(0, new Start(1, 10_000, on(0, 4, 12), 1.0, 1 / 2.4))
                .at(10, e -> at[1] = layer.status(0))
                .apply(20, new Resize(1, on(0, 4, 24), 2.4, 1.0))
                .at(30, e -> at[2] = layer.status(0));
        run(d);
        // idle: cpu 100 + 8 GPUs x 50 + base 100
        assertEquals(600, at[0].powerW(), 1e-9);
        // cpu 100 + 100 x 0.5 = 150; 4 busy GPUs 50 + 250/2.4 each; 4 idle x 50; base 100
        assertEquals(150 + 4 * (50 + 250 / 2.4) + 4 * 50 + 100, at[1].powerW(), 1e-9);
        assertEquals(0.5, at[1].cpuUtil(), 1e-12);
        assertEquals(4 / 2.4 / 8, at[1].gpuUtil(), 1e-12);
        // all cores, GPUs fully busy: cpu 200; 4 x 300 + 4 x 50; base 100
        assertEquals(200 + 4 * 300 + 4 * 50 + 100, at[2].powerW(), 1e-9);
        assertEquals(layer.hosts().get(0).getPower(), layer.status(0).powerW(), 1e-9);
    }

    @Test
    void cpuPowerCanUseCloudSimSpecPowerTables() {
        PowerSpec spec = new PowerSpec("PowerModelSpecPowerIbmX3550XeonX5675", 0, 0, 50, 300, 0);
        assertEquals(58.4, GpuHost.cpuModel(spec).getPower(0), 1e-9);   // SPECpower idle of this server
        PowerSpec bad = new PowerSpec("NoSuchModel", 0, 0, 50, 300, 0);
        assertThrows(IllegalArgumentException.class, () -> GpuHost.cpuModel(bad));
    }

    @Test
    void serverWithoutPowerSpecReportsNaN() {
        GpuLayer layer = cluster(1);
        assertTrue(Double.isNaN(layer.status(0).powerW()));
    }
}
