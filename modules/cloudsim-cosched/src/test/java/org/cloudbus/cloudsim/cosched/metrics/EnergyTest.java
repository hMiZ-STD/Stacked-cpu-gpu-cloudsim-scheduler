package org.cloudbus.cloudsim.cosched.metrics;

import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Energy and utilisation integrated over a run, checked against a hand calculation, and the rules for
 * power settings (no defaults, every number with a source).
 */
class EnergyTest {

    static final String PROFILES = Path.of("../../presets/profiles/synergy-v100/models").toAbsolutePath().normalize().toString();

    static final String POWER = "{ cpu_model: linear, cpu_idle_w: 100, cpu_max_w: 300, gpu_idle_w: 50, gpu_max_w: 250, base_w: 100, source: test }";

    static String yaml(String power, String energy) {
        return """
                name: energy
                cluster:
                  server_groups:
                    - { name: s, count: 1, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 500, net_gbps: 0%s }
                %s
                workload: { csv: jobs.csv }
                models: { profiles_dir: %s, scaling: linear, throughput_mode: table }
                candidates:
                  - name: fifo
                    trigger:    { name: event_driven }
                    ordering:   { name: fifo }
                    allocation: { name: gpu_proportional }
                    placement:  { name: consolidated }
                    lifecycle:  { name: non_preemptive }
                output_dir: out
                """.formatted(power == null ? "" : ", power: " + power, energy == null ? "" : energy, PROFILES);
    }

    static final String ENERGY = "energy: { pue: 1.5, pue_source: test, grid_g_co2_per_kwh: 500, grid_source: test }";

    /**
     * Job 1: transformer, 8 GPUs, all 24 cores, 0-100 s, GPUs fully busy (ideal speedup 1).
     * Job 2: res18, 4 GPUs, 12 cores, 100-200 s, at the fixed share (speedup 1). Its ideal is 9 cores per GPU = 36
     * cores, more than a server has, so the ideal is capped at the server's 24 cores (6 per GPU, speedup 1.75), as
     * Synergy caps it: its GPUs are busy 1/1.75 of the time. The other 4 GPUs are idle.
     *   0-100:   CPU 300 + GPUs 8 x 250 + base 100                           = 2400 W
     *   100-200: CPU 100 + 200 x 0.5 = 200; GPUs 4 x (50 + 200/1.75) + 4 x 50 = 857.14; base 100 -> 1157.14 W
     */
    @Test
    void energyAndUtilisationMatchAHandCalculation(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jobs.csv"), """
                job_id,arrival_s,gpus,model,iterations,iter_time_s
                1,0,8,transformer,100,1
                2,0,4,res18,100,1
                """);
        Path y = Files.writeString(dir.resolve("e.yaml"), yaml(POWER, ENERGY));
        ExperimentRunner.RunResult r = ExperimentRunner.run(y).get(0);
        Map<String, Object> s = r.summary();
        double b = 1 / 1.75;   // busy fraction of job 2's GPUs
        double itJ = 2400 * 100 + (200 + 4 * (50 + 200 * b) + 4 * 50 + 100) * 100;
        double tol = 0.002;   // CloudSim's 0.01 s steps
        assertEquals(200, (double) s.get("makespan_s"), 0.05);
        assertEquals(itJ / 3.6e6, (double) s.get("it_energy_kwh"), itJ / 3.6e6 * tol);
        assertEquals(itJ * 1.5 / 3.6e6, (double) s.get("facility_energy_kwh"), itJ * 1.5 / 3.6e6 * tol);
        assertEquals(itJ * 1.5 / 3.6e6 * 0.5, (double) s.get("co2_kg"), itJ * 1.5 / 3.6e6 * 0.5 * tol);
        assertEquals(itJ * 1.5 / 3.6e6 / 2, (double) s.get("kwh_per_job"), 1e-3);
        // samples: 100 x 128 (transformer) + 100 x 512 (res18)
        assertEquals(itJ * 1.5 / (100 * 128 + 100 * 512), (double) s.get("j_per_sample"), itJ * 1.5 / 64000 * tol);
        double stalledJ = 50 * (4 - 4 * b) * 100, idleJ = 50 * 4 * 100;
        assertEquals(stalledJ / itJ, (double) s.get("stalled_gpu_energy_share"), 1e-3);
        assertEquals(idleJ / itJ, (double) s.get("idle_gpu_energy_share"), 1e-3);
        double cpuJ = 300 * 100 + 200 * 100;
        assertEquals(cpuJ / itJ, (double) s.get("cpu_energy_share"), 1e-3);
        // utilisation over [0, 200]
        assertEquals((8 * 100 + 4 * 100) / 1600.0, (double) s.get("gpu_alloc_util"), 1e-3);
        assertEquals((8 * 100 + 4 * b * 100) / 1600.0, (double) s.get("gpu_busy_util"), 1e-3);
        assertEquals((24 * 100 + 12 * 100) / 4800.0, (double) s.get("cpu_alloc_util"), 1e-3);

        List<String> timeline = Files.readAllLines(r.outputDir().resolve("timeline.csv"));
        assertEquals("time_s,used_gpus,busy_gpus,used_cpus,it_power_w", timeline.get(0));
        assertTrue(timeline.get(1).startsWith("0.000000,0,"), timeline.get(1));     // idle before the first round
        assertTrue(timeline.stream().anyMatch(l -> l.endsWith(",2400.000")), timeline.toString());
        String manifest = Files.readString(r.outputDir().resolve("manifest.json"));
        assertTrue(manifest.contains("\"pue_source\" : \"test\""), "energy settings recorded");
        assertTrue(r.assumptions().stream().anyMatch(a -> a.contains("Source: test")), "power source stated");
    }

    @Test
    void withoutEnergyThereAreNoEnergyMetricsButUtilisationIsMeasured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jobs.csv"), "job_id,arrival_s,gpus,model,iterations,iter_time_s\n1,0,8,transformer,100,1\n");
        Map<String, Object> s = ExperimentRunner.run(Files.writeString(dir.resolve("e.yaml"), yaml(null, null))).get(0).summary();
        assertFalse(s.containsKey("it_energy_kwh"));
        assertEquals(1.0, (double) s.get("gpu_alloc_util"), 1e-3);
    }

    @Test
    void powerSettingsHaveNoDefaults(@TempDir Path dir) throws Exception {
        assertConfigError(dir, yaml(null, ENERGY), "needs a power section");
        assertConfigError(dir, yaml(POWER, null), "no energy section");
        assertConfigError(dir, yaml(POWER.replace("source: test", "source: \" \""), ENERGY), "source is required");
        assertConfigError(dir, yaml(POWER.replace("base_w: 100, ", ""), ENERGY), "base_w is required");
        assertConfigError(dir, yaml(POWER.replace("cpu_model: linear", "cpu_model: PowerModelSpecPowerIbmX3550XeonX5675"), ENERGY),
                "only used with cpu_model linear");
        assertConfigError(dir, yaml(POWER, ENERGY.replace("pue: 1.5, ", "")), "energy.pue is required");
        assertConfigError(dir, yaml(POWER, ENERGY.replace("grid_source: test", "grid_source: ''")), "grid_source is required");
        assertConfigError(dir, yaml(POWER.replace("base_w", "bas_w"), ENERGY), "base_w is required");   // a typo
    }

    @Test
    void specPowerCpuTablesCanBeUsed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jobs.csv"), "job_id,arrival_s,gpus,model,iterations,iter_time_s\n1,0,8,transformer,100,1\n");
        String p = "{ cpu_model: PowerModelSpecPowerIbmX3550XeonX5675, gpu_idle_w: 50, gpu_max_w: 250, base_w: 0, source: test }";
        Map<String, Object> s = ExperimentRunner.run(Files.writeString(dir.resolve("e.yaml"), yaml(p, ENERGY))).get(0).summary();
        // IBM x3550 (X5675) at 100% draws 222 W (CloudSim's SPECpower table); GPUs 8 x 250 W; 100 s
        assertEquals((222 + 2000) * 100 / 3.6e6, (double) s.get("it_energy_kwh"), 1e-4);
    }

    static void assertConfigError(Path dir, String yaml, String expected) throws Exception {
        Path y = Files.writeString(dir.resolve("bad.yaml"), yaml);
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.load(y));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
