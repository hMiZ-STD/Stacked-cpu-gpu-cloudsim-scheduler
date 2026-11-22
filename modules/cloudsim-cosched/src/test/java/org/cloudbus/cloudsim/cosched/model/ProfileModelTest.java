package org.cloudbus.cloudsim.cosched.model;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** L2 models against the real profiles converted from Synergy (presets/profiles/synergy-v100). */
class ProfileModelTest {

    static final Path PROFILES = Path.of("../../presets/profiles/synergy-v100");
    static final ServerGroup SYNERGY = ClusterSpec.synergyDefault().serverGroups().get(0);   // 24 CPUs, 8 GPUs, 500 GB

    record Job(int id, String model, int gpus) implements JobInfo {
        public double arrivalTime() { return 0; }
        public String team() { return Organisation.DEFAULT; }
        public String priority() { return Organisation.DEFAULT; }
        public double totalWork() { return 1; }
        public double remainingWork() { return 1; }
        public double remainingTimeAtProportionalShare() { return 1; }
        public double attainedServiceGpuSeconds() { return 0; }
        public double firstStartTime() { return Double.NaN; }
        public Allocation allocation() { return null; }
    }

    static ProfileRepository repo(String dir, ProfileRepository.Scaling s) {
        return ProfileRepository.load(PROFILES.resolve(dir), s);
    }

    static ProfileTableModel table(String dir) {
        return new ProfileTableModel(repo(dir, ProfileRepository.Scaling.LINEAR), ProfileTableModel.Mode.TABLE);
    }

    static ResourceVector perGpu(int gpus, double cpusPerGpu, double memPerGpu) {
        return new ResourceVector(gpus, cpusPerGpu * gpus, memPerGpu * gpus);
    }

    @Test
    void everySynergyProfileIsOneAtTheFixedShareAndItsSpeedupAtTheIdealDemand() {
        ProfileTableModel m = table("models");
        ProfileTableModel exact = new ProfileTableModel(repo("models", ProfileRepository.Scaling.LINEAR), ProfileTableModel.Mode.SYNERGY_TWO_POINT);
        int checked = 0;
        for (Profile p : repo("models", ProfileRepository.Scaling.LINEAR).all()) {
            if (p.gpuCount() != 1 || !Boolean.TRUE.equals(p.usedBySynergy())) continue;
            Job j = new Job(1, p.model(), 1);
            assertEquals(1.0, m.speedup(j, SYNERGY.proportionalShare(1), SYNERGY), 1e-9, p.model() + " at the fixed share");
            assertEquals(p.idealSpeedup(), m.speedup(j, m.idealDemand(j, SYNERGY), SYNERGY), 0.02, p.model() + " at its ideal demand");
            assertEquals(p.idealSpeedup(), exact.speedup(j, exact.idealDemand(j, SYNERGY), SYNERGY), 0, p.model() + " two-point");
            checked++;
        }
        assertTrue(checked >= 10, "checked " + checked + " profiles");
    }

    @Test
    void resNet18Is2point4AtNineCoresPerGpu() {
        ProfileTableModel m = table("models");
        Job j = new Job(1, "res18", 2);
        assertEquals(2.4, m.speedup(j, perGpu(2, 9, 62.5), SYNERGY), 1e-9);
        assertEquals(new ResourceVector(2, 18, 125), m.idealDemand(j, SYNERGY));
    }

    @Test
    void speedupIsRelativeToTheServersOwnFixedShare() {
        // Fig 12: 32-CPU servers, fixed share 4 cores/GPU. Synergy's models-4 states res18's speedup as 1.85.
        ServerGroup g32 = ClusterSpec.synergy(32).serverGroups().get(0);
        ProfileTableModel m = table("models-4");
        Job j = new Job(1, "res18", 1);
        assertEquals(1.0, m.speedup(j, g32.proportionalShare(1), g32), 1e-9);
        assertEquals(1.85, m.speedup(j, m.idealDemand(j, g32), g32), 0.01);
    }

    @Test
    void cpuPointsAreInterpolatedAndMemoryColumnsAreSteps() {
        ProfileTableModel m = table("models");
        Job j = new Job(1, "res18", 1);
        // res18 at full memory: 6 cores -> 1.75, 9 cores -> 2.4; 7.5 cores is half way
        assertEquals(1.75 + 0.5 * (2.4 - 1.75), m.speedup(j, perGpu(1, 7.5, 62.5), SYNERGY), 1e-9);
        // below the 62.5 GB column the 20.83 GB column applies (0.1 in every row)
        assertEquals(0.1, m.speedup(j, perGpu(1, 3, 40), SYNERGY), 1e-9);
    }

    @Test
    void beyondTheLastMeasuredPointThroughputIsHeldAndWarnedOnce() {
        ServerGroup big = new ServerGroup("big", 1, 1, "V100", 64, 500, 0, 0);
        ProfileTableModel m = table("models");
        Job j = new Job(1, "res18", 1);
        double at24 = m.lookup(repo("models", ProfileRepository.Scaling.LINEAR).profileFor("res18", "V100", 1), 24, 250, false);
        double fair = m.lookup(repo("models", ProfileRepository.Scaling.LINEAR).profileFor("res18", "V100", 1), 64, 500, false);
        assertEquals(at24, fair, 1e-12);
        m.speedup(j, new ResourceVector(1, 40, 250), big);
        m.speedup(j, new ResourceVector(1, 50, 250), big);
        assertEquals(1, m.warnings().size(), m.warnings().toString());
        assertTrue(m.warnings().get(0).contains("beyond"));
    }

    @Test
    void missingGpuTypeOrCountStopsTheRun() {
        ProfileRepository linear = repo("models", ProfileRepository.Scaling.LINEAR);
        var e = assertThrows(IllegalArgumentException.class, () -> linear.profileFor("res18", "A100", 1));
        assertTrue(e.getMessage().contains("A100"));
        assertThrows(IllegalArgumentException.class, () -> linear.requireAll(Set.of("res18", "nosuch"), Set.of("V100"), Set.of(1)));
        ProfileRepository exact = repo("models", ProfileRepository.Scaling.PER_GPU_COUNT);
        assertThrows(IllegalArgumentException.class, () -> exact.profileFor("bert", "V100", 2));   // only bert_1 exists
    }

    @Test
    void scalingChoosesTheProfileFile() {
        assertEquals(1, repo("models", ProfileRepository.Scaling.LINEAR).profileFor("res18", "V100", 4).gpuCount());
        assertEquals(4, repo("models", ProfileRepository.Scaling.PER_GPU_COUNT).profileFor("res18", "V100", 4).gpuCount());
    }

    @Test
    void localityAppliesOnlyWhenSpread() {
        LocalityModel loc = new LocalityModel(0.8);
        assertEquals(1.0, loc.factor(Allocation.single(0, perGpu(2, 3, 62.5))));
        assertEquals(0.8, loc.factor(new Allocation(List.of(
                new Allocation.Part(0, perGpu(1, 3, 62.5)), new Allocation.Part(1, perGpu(1, 3, 62.5))))));
        assertThrows(IllegalArgumentException.class, () -> new LocalityModel(0));
    }

    @Test
    void rateIsSpeedupTimesLocalityOverIterationTime() {
        ClusterSpec c = ClusterSpec.synergyDefault();
        RateCalculator rc = new RateCalculator(table("models"), new LocalityModel(0.8), InterferenceModel.NONE, c);
        Job j = new Job(1, "res18", 2);
        RateCalculator.Speed one = rc.speed(j, Allocation.single(0, perGpu(2, 9, 62.5)), 0.64);
        assertEquals(2.4 / 0.64, one.rate(), 1e-9);
        assertEquals(1.0, one.gpuBusyFraction(), 1e-9);
        RateCalculator.Speed spread = rc.speed(j, new Allocation(List.of(
                new Allocation.Part(0, perGpu(1, 9, 62.5)), new Allocation.Part(1, perGpu(1, 9, 62.5)))), 0.64);
        assertEquals(2.4 * 0.8 / 0.64, spread.rate(), 1e-9);
        RateCalculator.Speed fair = rc.speed(j, Allocation.single(0, SYNERGY.proportionalShare(2)), 0.64);
        assertEquals(1 / 0.64, fair.rate(), 1e-9);
        assertEquals(1 / 2.4, fair.gpuBusyFraction(), 1e-9);   // ResNet-18 at the fixed share: GPUs busy ~42%
    }
}
