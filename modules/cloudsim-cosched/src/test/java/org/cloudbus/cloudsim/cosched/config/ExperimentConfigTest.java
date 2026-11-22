package org.cloudbus.cloudsim.cosched.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentConfigTest {

    static final String YAML = """
            name: example
            cluster:
              server_groups:
                - { name: legacy-v100, count: 2, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 500, net_gbps: 40 }
            organisation:
              teams: [ { name: vision, gpu_quota: 8 }, { name: nlp } ]
            workload:
              csv: jobs.csv
              labeller: { name: class_split, params: { split: [20, 70, 10] } }
            models:
              profiles_dir: profiles/models
              scaling: linear
              throughput_mode: synergy_two_point
            candidates:
              - name: fifo-fair
                trigger:    { name: periodic, params: { round_s: 300 } }
                ordering:   { name: fifo }
                allocation: { name: gpu_proportional }
                placement:  { name: consolidated, params: { locality: strict } }
                lifecycle:  { name: non_preemptive }
            seeds: [42, 43]
            """;

    @Test
    void theDocumentedExampleParses(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("exp.yaml"), YAML);
        ExperimentConfig c = ExperimentConfig.load(y);
        assertEquals("example", c.name());
        assertEquals(2, c.cluster().serverCount());
        assertEquals(8, c.organisation().team("vision").gpuQuota());
        assertEquals("class_split", c.workload().labeller().name());
        assertEquals("synergy_two_point", c.models().throughputMode());
        assertEquals(1.0, c.models().localityPenalty());
        assertEquals(300, c.candidates().get(0).trigger().params().get("round_s"));
        assertEquals(2, c.seeds().size());
        assertEquals("out/example", c.outputDir());
        assertEquals(dir.resolve("jobs.csv").toAbsolutePath(), ExperimentConfig.resolve(y, c.workload().csv()));
    }

    @Test
    void unknownKeysAreErrors(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("exp.yaml"), YAML.replace("seeds:", "seedz:"));
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.load(y));
        assertTrue(e.getMessage().contains("seedz"), e.getMessage());
    }

    @Test
    void missingPartsOfACandidateAreErrors(@TempDir Path dir) throws Exception {
        Path y = Files.writeString(dir.resolve("exp.yaml"), YAML.replace("    lifecycle:  { name: non_preemptive }\n", ""));
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.load(y));
        assertTrue(e.getMessage().contains("needs a lifecycle"), e.getMessage());
    }
}
