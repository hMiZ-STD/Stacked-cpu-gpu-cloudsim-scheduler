package org.cloudbus.cloudsim.cosched.core;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreTypesTest {

    // ---- a minimal ClusterState for tests ----

    record Job(int id, int gpus, Allocation allocation) implements JobInfo {
        public double arrivalTime() { return id; }
        public String model() { return "resnet18"; }
        public String team() { return Organisation.DEFAULT; }
        public String priority() { return Organisation.DEFAULT; }
        public double totalWork() { return 1000; }
        public double remainingWork() { return 1000; }
        public double remainingTimeAtProportionalShare() { return 1000; }
        public double attainedServiceGpuSeconds() { return 0; }
        public double firstStartTime() { return Double.NaN; }
    }

    record State(ClusterSpec spec, List<JobInfo> activeJobs) implements ClusterState {
        public double now() { return 0; }
        public List<Server> servers() { return spec.servers(); }
        public Organisation organisation() { return Organisation.single(); }
        public ResourceVector free(int s) {
            ResourceVector used = ResourceVector.ZERO;
            for (JobInfo j : activeJobs) if (j.isRunning()) used = used.plus(j.allocation().on(s));
            return spec.servers().get(s).capacity().minus(used).toVector();
        }
    }

    static final ClusterSpec SYNERGY = ClusterSpec.synergyDefault();

    @Test
    void clusterSpecExpandsGroupsInOrder() {
        ClusterSpec c = new ClusterSpec(List.of(
                new ServerGroup("a100", 2, 8, "A100", 64, 1024, 2000, 100),
                new ServerGroup("v100", 3, 8, "V100", 24, 500, 500, 40)));
        List<Server> s = c.servers();
        assertEquals(5, s.size());
        assertEquals("A100", s.get(1).gpuType());
        assertEquals("V100", s.get(2).gpuType());
        assertEquals(4, s.get(4).index());
        assertEquals(new ResourceVector(40, 200, 3548), c.totalCapacity());
        assertEquals(128, SYNERGY.totalCapacity().gpus());
    }

    @Test
    void clusterSpecReadsTheDocumentedYaml() throws Exception {
        String yaml = """
                server_groups:
                  - { name: training-a100, count: 8,  gpus: 8, gpu_type: A100, cpus: 64, mem_gb: 1024, storage_mbps: 2000, net_gbps: 100 }
                  - { name: legacy-v100,   count: 16, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500,  storage_mbps: 500,  net_gbps: 40 }
                racks: 2
                """;
        ClusterSpec c = new YAMLMapper().readValue(yaml, ClusterSpec.class);
        assertEquals(24, c.serverCount());
        assertEquals(2, c.racks());
        assertEquals(java.util.Set.of("A100", "V100"), c.gpuTypes());
    }

    @Test
    void invalidServerGroupIsRejectedWithItsName() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> new ServerGroup("bad", 1, 8, "V100", 0, 500, 500, 40));
        assertTrue(e.getMessage().contains("'bad'"));
    }

    @Test
    void proportionalShareIsTheFixedRatio() {
        ServerGroup g = SYNERGY.serverGroups().get(0);
        assertEquals(new ResourceVector(2, 6, 125), g.proportionalShare(2));   // 24/8 cores, 500/8 GB per GPU
    }

    @Test
    void emptyPlanRejectsWhatDoesNotFit() {
        List<JobInfo> jobs = List.of(new Job(1, 2, null), new Job(2, 8, null));
        RoundPlan plan = new RoundPlan(new State(SYNERGY, jobs), RoundPlan.Start.EMPTY);
        assertTrue(plan.assign(1, Allocation.single(0, ResourceVector.of(2, 18, 100))));
        assertEquals(ResourceVector.of(6, 6, 400), plan.free(0));
        assertFalse(plan.assign(2, Allocation.single(0, ResourceVector.of(8, 6, 100))), "GPUs left: 6");
        assertFalse(plan.assign(1, Allocation.single(1, ResourceVector.of(1, 3, 10))), "gang: must be 2 GPUs");
        assertNull(plan.get(2));
    }

    @Test
    void reviseCountsTheJobsOwnEntryAsFree() {
        List<JobInfo> jobs = List.of(new Job(1, 2, null), new Job(2, 6, null));
        RoundPlan plan = new RoundPlan(new State(SYNERGY, jobs), RoundPlan.Start.EMPTY);
        assertTrue(plan.assign(1, Allocation.single(0, ResourceVector.of(2, 24, 100))));       // took every core
        assertFalse(plan.assign(2, Allocation.single(0, ResourceVector.of(6, 18, 300))));
        assertTrue(plan.assign(1, Allocation.single(0, ResourceVector.of(2, 6, 100))));        // Tune: shrink job 1
        assertTrue(plan.assign(2, Allocation.single(0, ResourceVector.of(6, 18, 300))));
        assertEquals(ResourceVector.of(0, 0, 100), plan.free(0));
        assertEquals(List.of(1, 2), plan.jobsOn(0));
    }

    @Test
    void carryRunningKeepsCurrentAllocations() {
        Allocation running = new Allocation(List.of(
                new Allocation.Part(0, ResourceVector.of(4, 12, 250)),
                new Allocation.Part(1, ResourceVector.of(4, 12, 250))));
        List<JobInfo> jobs = List.of(new Job(1, 8, running), new Job(2, 4, null));
        RoundPlan plan = new RoundPlan(new State(SYNERGY, jobs), RoundPlan.Start.CARRY_RUNNING);
        assertEquals(running, plan.get(1));
        assertEquals(ResourceVector.of(4, 12, 250), plan.free(0));
        RoundPlan empty = new RoundPlan(new State(SYNERGY, jobs), RoundPlan.Start.EMPTY);
        assertNull(empty.get(1));
        assertEquals(SYNERGY.servers().get(0).capacity(), empty.free(0));
    }

    @Test
    void allocationRejectsTwoPartsOnOneServer() {
        assertThrows(IllegalArgumentException.class, () -> new Allocation(List.of(
                new Allocation.Part(3, ResourceVector.of(1, 1, 1)),
                new Allocation.Part(3, ResourceVector.of(1, 1, 1)))));
    }

    @Test
    void samePlacementIgnoresCoresAndMemory() {
        Allocation a = Allocation.single(2, ResourceVector.of(2, 6, 100));
        assertTrue(a.samePlacement(Allocation.single(2, ResourceVector.of(2, 18, 50))));
        assertFalse(a.samePlacement(Allocation.single(3, ResourceVector.of(2, 6, 100))));
    }

    // ---- registry and params ----

    interface Ordering { double roundS(); }

    @Test
    void registryBuildsByNameAndReportsKnownNames() {
        PolicyRegistry reg = new PolicyRegistry()
                .register(Ordering.class, "fifo", p -> { double r = p.getDouble("round_s", 300, 1, 86400); return () -> r; });
        Params params = new Params("ordering fifo", Map.of("round_s", 60));
        assertEquals(60, reg.create(Ordering.class, "fifo", params).roundS());
        assertEquals(Map.of("round_s", 60.0), params.resolved());

        var unknown = assertThrows(IllegalArgumentException.class,
                () -> reg.create(Ordering.class, "fofi", Params.none("x")));
        assertTrue(unknown.getMessage().contains("[fifo]"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> reg.register(Ordering.class, "fifo", p -> () -> 1));
    }

    @Test
    void paramsAreRangeCheckedAndTyposCaught() {
        PolicyRegistry reg = new PolicyRegistry()
                .register(Ordering.class, "fifo", p -> { double r = p.getDouble("round_s", 300, 1, 86400); return () -> r; });
        var range = assertThrows(IllegalArgumentException.class,
                () -> reg.create(Ordering.class, "fifo", new Params("ordering fifo", Map.of("round_s", 0))));
        assertTrue(range.getMessage().contains("outside"), range.getMessage());
        var typo = assertThrows(IllegalArgumentException.class,
                () -> reg.create(Ordering.class, "fifo", new Params("ordering fifo", Map.of("round_sec", 60))));
        assertTrue(typo.getMessage().contains("round_sec"), typo.getMessage());
    }

    @Test
    void organisationDefaultsToOneTeam() {
        Organisation o = Organisation.single();
        assertTrue(o.hasTeam(Organisation.DEFAULT));
        assertNull(o.team(Organisation.DEFAULT).gpuQuota());
        assertThrows(IllegalArgumentException.class, () -> new Organisation(
                new ArrayList<>(List.of(new Organisation.Team("a", 8), new Organisation.Team("a", 4))), null));
    }
}
