package org.cloudbus.cloudsim.cosched.metrics;

import com.opencsv.CSVReaderHeaderAware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation step 0, frozen as a regression test: replaying Synergy's own job list (presets/synergy-replica)
 * must give every measured job (ids 4000-4999) the JCT Synergy's simulator gave it, for FIFO+tune and FIFO+fair.
 */
class SynergyValidationTest {

    static final Path PRESETS = Path.of("../../presets").toAbsolutePath().normalize();

    static Map<Integer, Double> read(Path csv, String idCol, String jctCol) throws Exception {
        Map<Integer, Double> m = new HashMap<>();
        try (CSVReaderHeaderAware r = new CSVReaderHeaderAware(new FileReader(csv.toFile()))) {
            Map<String, String> row;
            while ((row = r.readMap()) != null) {
                String jct = row.get(jctCol);
                if (jct != null && !jct.isBlank()) m.put(Integer.parseInt(row.get(idCol)), Double.parseDouble(jct));
            }
        }
        return m;
    }

    @Test
    void replicaMatchesSynergyJobByJob(@TempDir Path dir) throws Exception {
        String y = Files.readString(PRESETS.resolve("synergy-replica/synergy-replica.yaml"))
                .replace("csv: jobs.csv", "csv: " + PRESETS.resolve("synergy-replica/jobs.csv"))
                .replace("../profiles/synergy-v100/models", PRESETS.resolve("profiles/synergy-v100/models").toString())
                .replace("../../out/synergy-replica", dir.resolve("out").toString());
        Path yaml = Files.writeString(dir.resolve("replica.yaml"), y);
        List<ExperimentRunner.RunResult> results = ExperimentRunner.run(yaml);

        for (String[] c : new String[][]{{"fifo-tune", "synergy_ref_fifo_tune_9jph.csv", "21.8766"},
                                          {"fifo-fair", "synergy_ref_fifo_fair_9jph.csv", "81.0886"}}) {
            ExperimentRunner.RunResult r = results.stream().filter(x -> x.candidate().equals(c[0])).findFirst().orElseThrow();
            Map<Integer, Double> ours = read(r.outputDir().resolve("jobs.csv"), "job_id", "jct_s");
            Map<Integer, Double> ref = read(PRESETS.resolve("reference").resolve(c[1]), "job id", "jct_seconds");
            assertEquals(1000, ref.size());
            double sum = 0, worst = 0;
            for (var e : ref.entrySet()) {
                double d = Math.abs(ours.get(e.getKey()) - e.getValue());
                worst = Math.max(worst, d);
                sum += ours.get(e.getKey());
            }
            assertTrue(worst < 1, c[0] + ": worst difference to Synergy " + worst + " s");
            assertEquals(Double.parseDouble(c[2]), sum / ref.size() / 3600, 5e-5, c[0] + " mean JCT of jobs 4000-4999");
        }
    }
}
