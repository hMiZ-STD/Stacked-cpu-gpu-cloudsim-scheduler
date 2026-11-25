package org.cloudbus.cloudsim.cosched.metrics;

import com.opencsv.CSVReaderHeaderAware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4: our simulator replays Synergy's own runs (presets/synergy-validation, built by
 * tools/synergy_validation) and every measured job (ids 4000-4999) must get the JCT Synergy's simulator gave it.
 *
 * <p>Opt-in because it is slow (all 30 took 68 minutes on this 16-core machine while Synergy runs shared it):
 * {@code -Dcosched.validate=subset} runs one configuration from each family, {@code -Dcosched.validate=all} runs
 * all 30 (load sweeps, FIFO/SRTF/LAS/FTF with fixed share, Greedy and Tune, the Fig. 12 CPU ratios, the Fig. 11
 * splits, multi-GPU jobs). Without the property it is skipped; {@link SynergyValidationTest} (step 0) always runs.
 * <pre>mvn -pl modules/cloudsim-cosched test -Dtest=SynergyValidationSuiteTest -DargLine="-Dcosched.validate=all -Xmx3g"</pre>
 */
class SynergyValidationSuiteTest {

    static final Path SUITE = Path.of("../../presets/synergy-validation").toAbsolutePath().normalize();

    /** One configuration from each family; the multi-GPU LAS+tune and FTF+tune cases found real bugs. */
    static final List<String> SUBSET = List.of("multi4-las-tune", "load9-ftf-tune", "cpu48-fifo-tune",
            "split50050-fifo-greedy", "multi55-srtf-tune", "load6-fifo-fair");

    record Result(String name, int measured, int exact, double worstS, double meanOursH, double meanRefH) {
    }

    static Map<Integer, Double> read(Path csv, String idCol, String jctCol) throws Exception {
        Map<Integer, Double> m = new HashMap<>();
        try (CSVReaderHeaderAware r = new CSVReaderHeaderAware(new FileReader(csv.toFile()))) {
            Map<String, String> row;
            while ((row = r.readMap()) != null) m.put(Integer.parseInt(row.get(idCol)), Double.parseDouble(row.get(jctCol)));
        }
        return m;
    }

    static Result run(String name, Path tmp) throws Exception {
        Path dir = SUITE.resolve(name);
        String y = Files.readString(dir.resolve("exp.yaml"))
                .replace("csv: jobs.csv", "csv: " + dir.resolve("jobs.csv"))
                .replace("../../profiles/", SUITE.getParent().resolve("profiles") + "/")
                .replace("output_dir: ../../../out/synergy-validation/" + name, "output_dir: " + tmp.resolve("out"));
        List<ExperimentRunner.RunResult> results = ExperimentRunner.run(Files.writeString(tmp.resolve(name + ".yaml"), y));
        Map<Integer, Double> ours = read(results.get(0).outputDir().resolve("jobs.csv"), "job_id", "jct_s");
        Map<Integer, Double> ref = read(dir.resolve("ref_jct.csv"), "job id", "jct_seconds");
        int exact = 0;
        double worst = 0, sumOurs = 0, sumRef = 0;
        for (var e : ref.entrySet()) {
            double o = ours.get(e.getKey()), d = Math.abs(o - e.getValue());
            worst = Math.max(worst, d);
            if (d < 1) exact++;
            sumOurs += o;
            sumRef += e.getValue();
        }
        return new Result(name, ref.size(), exact, worst, sumOurs / ref.size() / 3600, sumRef / ref.size() / 3600);
    }

    @Test
    void ourSimulatorReproducesSynergyJobByJob(@TempDir Path tmp) throws Exception {
        String mode = System.getProperty("cosched.validate", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(mode.equals("subset") || mode.equals("all"),
                "Synergy validation suite skipped; run with -Dcosched.validate=subset or =all");
        List<String> names = new ArrayList<>(SUBSET);
        if (mode.equals("all")) {
            names.clear();
            try (CSVReaderHeaderAware r = new CSVReaderHeaderAware(new FileReader(SUITE.resolve("manifest.csv").toFile()))) {
                Map<String, String> row;
                while ((row = r.readMap()) != null) names.add(row.get("name"));
            }
            assertEquals(30, names.size());
        }
        StringBuilder report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        for (String name : names) {
            Result r = run(name, tmp);
            report.append(String.format("%-24s exact %4d/%d  worst %.1f s  mean %.4f vs %.4f h%n", r.name(), r.exact(), r.measured(),
                    r.worstS(), r.meanOursH(), r.meanRefH()));
            if (r.exact() != r.measured() || r.measured() != 1000) failures.add(r.name());
        }
        System.out.print("\n" + report);
        assertTrue(failures.isEmpty(), "configurations that differ from Synergy: " + failures + "\n" + report);
    }
}
