package org.cloudbus.cloudsim.cosched.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A whole comparison: generated workloads, candidates x seeds x sweep values, then the report. */
class ComparisonRunnerTest {

    static String yaml(String sweepPath, String values) {
        return """
                name: cmp
                cluster:
                  server_groups:
                    - { name: s, count: 2, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 500, net_gbps: 0 }
                workload:
                  generator: { name: poisson, params: { jobs_per_hour: 2, jobs: 60, gpus: synergy_mix } }
                  labeller: { name: class_split, params: { split: [20, 70, 10] } }
                  measure: { from_job: 10, to_job: 49 }
                models: { profiles_dir: %s, scaling: linear, throughput_mode: synergy_two_point }
                candidates:
                  - name: fair
                    trigger: { name: periodic, params: { round_s: 300 } }
                    ordering: { name: fifo }
                    allocation: { name: gpu_proportional }
                    placement: { name: first_fit }
                    lifecycle: { name: round_based }
                  - name: tune
                    trigger: { name: periodic, params: { round_s: 300 } }
                    ordering: { name: fifo }
                    allocation: { name: synergy_tune }
                    placement: { name: first_fit }
                    lifecycle: { name: round_based }
                seeds: [11, 12]
                sweeps: [ { name: load, path: %s, values: %s } ]
                report:
                  objectives: [ { metric: mean_jct_h, weight: 1 }, { metric: p99_jct_h, weight: 1 } ]
                output_dir: out
                """.formatted(EnergyTest.PROFILES, sweepPath, values);
    }

    @Test
    void runsEveryCombinationAndWritesTheReport(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("cmp.yaml"), yaml("workload.generator.params.jobs_per_hour", "[1, 3]"));
        ComparisonRunner.Result r = ComparisonRunner.run(y);
        assertEquals(2 * 2 * 2, r.runs().size());
        Path out = dir.resolve("out");
        for (String p : List.of("load=1", "load=3"))
            for (String c : List.of("fair", "tune"))
                for (String s : List.of("seed-11", "seed-12"))
                    for (String f : List.of("jobs.csv", "manifest.json", "timeline.csv", "workload.csv"))
                        assertTrue(Files.exists(out.resolve(p).resolve(c).resolve(s).resolve(f)), p + "/" + c + "/" + s + "/" + f);
        // common random numbers: one workload per seed, shared by every candidate; seeds differ
        String fair11 = Files.readString(out.resolve("load=1/fair/seed-11/workload.csv"));
        assertEquals(fair11, Files.readString(out.resolve("load=1/tune/seed-11/workload.csv")));
        assertNotEquals(fair11, Files.readString(out.resolve("load=1/fair/seed-12/workload.csv")));
        assertNotEquals(fair11, Files.readString(out.resolve("load=3/fair/seed-11/workload.csv")), "the sweep changed the load");
        // JCT statistics over jobs 10-49 only
        ExperimentRunner.RunResult first = r.runs().get(0);
        assertEquals(60, first.summary().get("jobs"));
        assertEquals(40, first.summary().get("jobs_measured"));
        assertEquals(java.util.Map.of("load", 1), first.point());
        assertTrue(Files.exists(r.reportDir().resolve("decision-report.tex")));
        assertTrue(Files.exists(r.reportDir().resolve("sweep_stability.csv")));
        assertEquals(1 + 8, Files.readAllLines(r.reportDir().resolve("runs.csv")).size());
        // a generated workload can be replayed exactly from its workload.csv (recorded in the manifest)
        assertTrue(Files.readString(out.resolve("load=1/fair/seed-11/manifest.json")).contains("\"generated\" : true"));
    }

    @Test
    void aBadSweepFailsBeforeAnyRun(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("cmp.yaml"), yaml("cluster.server_groups.0.cpus", "[24, -1]"));
        var e = assertThrows(IllegalArgumentException.class, () -> ComparisonRunner.run(y));
        assertTrue(e.getMessage().contains("sweep point load=-1") && e.getMessage().contains("cpus must be > 0"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("out")), "no run started");
    }

    @Test
    void experimentRunnerRefusesSweeps(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("cmp.yaml"), yaml("workload.generator.params.jobs_per_hour", "[1]"));
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.run(y));
        assertTrue(e.getMessage().contains("ComparisonRunner"), e.getMessage());
    }
}
