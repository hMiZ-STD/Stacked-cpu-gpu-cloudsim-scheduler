package org.cloudbus.cloudsim.cosched.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SweepsTest {

    static ObjectNode tree(String yaml) throws Exception {
        return (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(yaml);
    }

    static final String BASE = """
            name: t
            cluster:
              server_groups:
                - { name: a, count: 2, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 0, net_gbps: 0 }
                - { name: b, count: 1, gpus: 4, gpu_type: V100, cpus: 16, mem_gb: 256, storage_mbps: 0, net_gbps: 0 }
            workload: { generator: { name: poisson, params: { jobs_per_hour: 9, jobs: 10 } }, labeller: { name: class_split } }
            models: { profiles_dir: p }
            candidates:
              - { name: c1, trigger: { name: periodic }, ordering: { name: fifo }, allocation: { name: gpu_proportional },
                  placement: { name: first_fit }, lifecycle: { name: round_based } }
              - { name: c2, trigger: { name: periodic }, ordering: { name: fifo }, allocation: { name: synergy_tune },
                  placement: { name: first_fit }, lifecycle: { name: round_based } }
            """;

    @Test
    void setsNestedValuesListPositionsAndEveryElement() throws Exception {
        ObjectNode t = tree(BASE);
        Sweeps.set(t, "cluster.server_groups.1.cpus", IntNode.valueOf(32));
        assertEquals(32, t.at("/cluster/server_groups/1/cpus").asInt());
        assertEquals(24, t.at("/cluster/server_groups/0/cpus").asInt());
        Sweeps.set(t, "cluster.server_groups.*.count", IntNode.valueOf(5));
        assertEquals(5, t.at("/cluster/server_groups/0/count").asInt());
        assertEquals(5, t.at("/cluster/server_groups/1/count").asInt());
        Sweeps.set(t, "candidates.*.trigger.params.round_s", IntNode.valueOf(60));   // creates the params map
        assertEquals(60, t.at("/candidates/1/trigger/params/round_s").asInt());
        assertEquals(60, ExperimentConfig.bind(t, "t").candidates().get(0).trigger().params().get("round_s"));
    }

    @Test
    void badPathsFailClearly() throws Exception {
        ObjectNode t = tree(BASE);
        var e = assertThrows(IllegalArgumentException.class, () -> Sweeps.set(t, "cluster.server_groups.7.cpus", IntNode.valueOf(1)));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Sweeps.set(t, "name.x", IntNode.valueOf(1)));
        assertTrue(e.getMessage().contains("is a value"), e.getMessage());
        // a misspelt key is created, and then rejected by validation as an unknown key
        Sweeps.set(t, "cluster.server_groups.0.cpu", IntNode.valueOf(32));
        e = assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.bind(t, "t"));
        assertTrue(e.getMessage().contains("cpu"), e.getMessage());
    }

    @Test
    void sweepsMultiplyInOrderWithLabels() throws Exception {
        ObjectNode t = tree(BASE);
        List<ExperimentConfig.Sweep> s = List.of(
                new ExperimentConfig.Sweep("load", "workload.generator.params.jobs_per_hour", null, List.of(6, 9)),
                new ExperimentConfig.Sweep(null, "cluster.server_groups.0.cpus", null, List.of(24, 32, 48)));
        List<Sweeps.Point> p = Sweeps.expand(t, s);
        assertEquals(6, p.size());
        assertEquals(List.of("load=6_cpus=24", "load=6_cpus=32", "load=6_cpus=48", "load=9_cpus=24", "load=9_cpus=32", "load=9_cpus=48"),
                p.stream().map(Sweeps.Point::label).toList());
        assertEquals(Map.of("load", 9, "cpus", 32), p.get(4).values());
        ExperimentConfig c = ExperimentConfig.bind(p.get(4).tree(), "t");
        assertEquals(32, c.cluster().serverGroups().get(0).cpus());
        assertEquals(9, c.workload().generator().params().get("jobs_per_hour"));
        assertEquals(24, ExperimentConfig.bind(t, "t").cluster().serverGroups().get(0).cpus(), "the original tree is untouched");
    }

    @Test
    void severalPathsCanChangeTogether() throws Exception {
        ObjectNode t = tree(BASE);
        var s = new ExperimentConfig.Sweep("cpus", null, List.of("cluster.server_groups.0.cpus", "models.profiles_dir"),
                List.of(List.of(24, "models"), List.of(32, "models-4")));
        List<Sweeps.Point> p = Sweeps.expand(t, List.of(s));
        assertEquals(List.of("cpus=24", "cpus=32"), p.stream().map(Sweeps.Point::label).toList());
        ExperimentConfig c = ExperimentConfig.bind(p.get(1).tree(), "t");
        assertEquals(32, c.cluster().serverGroups().get(0).cpus());
        assertEquals("models-4", c.models().profilesDir());
        var e = assertThrows(IllegalArgumentException.class, () -> new ExperimentConfig.Sweep("x", null,
                List.of("a", "b"), List.of(List.of(1))));
        assertTrue(e.getMessage().contains("one per path"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> new ExperimentConfig.Sweep("x", "seeds", null, List.of(1)));
        assertTrue(e.getMessage().contains("cannot sweep"), e.getMessage());
    }
}
