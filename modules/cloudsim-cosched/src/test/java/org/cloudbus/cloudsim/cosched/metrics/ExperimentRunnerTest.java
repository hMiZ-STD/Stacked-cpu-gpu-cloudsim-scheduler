package org.cloudbus.cloudsim.cosched.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The first end-to-end runs: the smoke preset through YAML, workload, models, broker and GPU layer. */
class ExperimentRunnerTest {

    static final Path PRESETS = Path.of("../../presets").toAbsolutePath().normalize();

    /** Copies the smoke preset into {@code dir}, pointing at the real profiles and writing output there. */
    static Path smoke(Path dir, String extraEdit) throws Exception {
        Files.copy(PRESETS.resolve("smoke/jobs.csv"), dir.resolve("jobs.csv"));
        String y = Files.readString(PRESETS.resolve("smoke/smoke.yaml"))
                .replace("../profiles/synergy-v100/models", PRESETS.resolve("profiles/synergy-v100/models").toString())
                .replace("../../out/smoke", dir.resolve("out").toString());
        if (extraEdit != null) y = y.replace("{ name: fifo }", extraEdit);
        return Files.writeString(dir.resolve("smoke.yaml"), y);
    }

    @Test
    void smokeRunFinishesEveryJobAndWritesResultsAndManifest(@TempDir Path dir) throws Exception {
        List<ExperimentRunner.RunResult> results = ExperimentRunner.run(smoke(dir, null));
        assertEquals(2, results.size());
        for (ExperimentRunner.RunResult r : results) {
            assertEquals(20, r.summary().get("jobs"));
            List<String> lines = Files.readAllLines(r.outputDir().resolve("jobs.csv"));
            assertEquals(JobMetrics.HEADER, lines.get(0));
            assertEquals(21, lines.size());
            JsonNode m = new ObjectMapper().readTree(r.outputDir().resolve("manifest.json").toFile());
            assertEquals("smoke", m.get("experiment").asText());
            assertEquals(64, m.get("config").get("sha256").asText().length());
            assertTrue(m.get("plugins").has("placement"));
            assertEquals("strict", m.get("plugins").get("placement").get("params").get("locality").asText());
        }
        JsonNode periodic = new ObjectMapper().readTree(results.get(0).outputDir().resolve("manifest.json").toFile());
        assertEquals(300.0, periodic.get("plugins").get("trigger").get("params").get("round_s").asDouble());
        // without waiting for 300 s rounds, jobs finish at least as early on average
        double meanPeriodic = (double) results.get(0).summary().get("mean_jct_h");
        double meanEvent = (double) results.get(1).summary().get("mean_jct_h");
        assertTrue(meanEvent <= meanPeriodic, meanEvent + " vs " + meanPeriodic);
    }

    /** The synergy-replica preset's wiring, run on the smoke jobs (its real jobs.csv comes from a Synergy run). */
    @Test
    void synergyReplicaPresetRunsAllThreeVariants(@TempDir Path dir) throws Exception {
        Files.copy(PRESETS.resolve("smoke/jobs.csv"), dir.resolve("jobs.csv"));
        String y = Files.readString(PRESETS.resolve("synergy-replica/synergy-replica.yaml"))
                .replace("../profiles/synergy-v100/models", PRESETS.resolve("profiles/synergy-v100/models").toString())
                .replace("../../out/synergy-replica", dir.resolve("out").toString());
        List<ExperimentRunner.RunResult> results = ExperimentRunner.run(Files.writeString(dir.resolve("replica.yaml"), y));
        assertEquals(List.of("fifo-tune", "fifo-fair", "fifo-greedy"), results.stream().map(ExperimentRunner.RunResult::candidate).toList());
        for (ExperimentRunner.RunResult r : results) {
            assertEquals(20, r.summary().get("jobs"));
            for (String line : Files.readAllLines(r.outputDir().resolve("jobs.csv")).subList(1, 21)) {
                double finish = Double.parseDouble(line.split(",")[5]);
                assertEquals(0, finish % 300, 1e-6, "Synergy's rounds: every finish on a round boundary: " + line);
            }
        }
        double tune = (double) results.get(0).summary().get("mean_jct_h"), fair = (double) results.get(1).summary().get("mean_jct_h");
        assertTrue(tune < fair, "Tune " + tune + " h should beat the fixed share " + fair + " h on an uncontended cluster");
    }

    @Test
    void sameInputsGiveIdenticalResults(@TempDir Path a, @TempDir Path b) throws Exception {
        ExperimentRunner.RunResult ra = ExperimentRunner.run(smoke(a, null)).get(0);
        ExperimentRunner.RunResult rb = ExperimentRunner.run(smoke(b, null)).get(0);
        assertEquals(Files.readString(ra.outputDir().resolve("jobs.csv")), Files.readString(rb.outputDir().resolve("jobs.csv")));
    }

    @Test
    void unknownPolicyNameStopsTheRunWithTheKnownNames(@TempDir Path dir) throws Exception {
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.run(smoke(dir, "{ name: fofo }")));
        assertTrue(e.getMessage().contains("fofo") && e.getMessage().contains("fifo"), e.getMessage());
    }

    @Test
    void outOfRangeParameterStopsTheRun(@TempDir Path dir) throws Exception {
        Path y = smoke(dir, null);
        Files.writeString(y, Files.readString(y).replace("round_s: 300", "round_s: 0"));
        var e = assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.run(y));
        assertTrue(e.getMessage().contains("round_s"), e.getMessage());
    }
}
