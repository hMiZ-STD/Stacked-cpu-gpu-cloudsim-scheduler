package org.cloudbus.cloudsim.cosched.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The decision report's arithmetic on hand-made results (no simulation). */
class DecisionReportTest {

    static ExperimentConfig cfg(String report) throws Exception {
        return cfg(report, "[1, 2, 3]");
    }

    static ExperimentConfig cfg(String report, String seeds) throws Exception {
        String y = """
                name: rep_test
                cluster: { server_groups: [ { name: a, count: 1, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 0, net_gbps: 0 } ] }
                workload: { csv: jobs.csv }
                models: { profiles_dir: p }
                candidates:
                  - { name: A, trigger: { name: periodic }, ordering: { name: fifo }, allocation: { name: gpu_proportional }, placement: { name: first_fit }, lifecycle: { name: round_based } }
                  - { name: B, trigger: { name: periodic }, ordering: { name: fifo }, allocation: { name: synergy_tune }, placement: { name: first_fit }, lifecycle: { name: round_based } }
                  - { name: C_x, trigger: { name: periodic }, ordering: { name: srtf }, allocation: { name: synergy_tune }, placement: { name: first_fit }, lifecycle: { name: round_based } }
                seeds:""" + " " + seeds + "\n" + report;
        return ExperimentConfig.bind(new ObjectMapper(new YAMLFactory()).readTree(y), "test");
    }

    static final String REPORT = """
            report:
              metrics: [mean_jct_h, p99_jct_h, kwh_per_job]
              objectives: [ { metric: mean_jct_h, weight: 2 }, { metric: kwh_per_job, weight: 1 } ]
            """;

    /** mean JCT, p99 JCT, kWh per job on seeds 1, 2, 3. */
    static final Map<String, double[][]> DATA = Map.of(
            "A", new double[][]{{10, 10, 10}, {1, 2, 10}, {5, 5, 5}},
            "B", new double[][]{{12, 12, 12}, {2, 3, 30}, {4, 4, 4}},
            "C_x", new double[][]{{11, 9, 13}, {3, 4, 40}, {6, 6, 6}});

    static List<ExperimentRunner.RunResult> results(Map<String, Object> point, Map<String, double[][]> data) {
        List<ExperimentRunner.RunResult> l = new ArrayList<>();
        for (String c : List.of("A", "B", "C_x"))
            for (int s = 0; s < 3; s++) {
                Map<String, Object> sum = new LinkedHashMap<>();
                sum.put("jobs", 100);
                sum.put("mean_jct_h", data.get(c)[0][s]);
                sum.put("p99_jct_h", data.get(c)[1][s]);
                sum.put("kwh_per_job", data.get(c)[2][s]);
                l.add(new ExperimentRunner.RunResult(c, s + 1, Path.of("x"), sum, point, List.of("assume 100% & $5 {x}")));
            }
        return l;
    }

    @Test
    void statsRanksScoresAndParetoByHand() throws Exception {
        DecisionReport r = new DecisionReport(cfg(REPORT), results(Map.of(), DATA));
        DecisionReport.Stats s = r.stats("", "C_x", "mean_jct_h");
        assertEquals(11, s.mean(), 1e-12);
        assertEquals(2, s.sd(), 1e-12);
        assertEquals(4.303 * 2 / Math.sqrt(3), s.ci95(), 1e-9);
        assertEquals(Map.of("A", 1, "B", 3, "C_x", 2), r.ranks("", "mean_jct_h"));
        assertEquals(Map.of("A", 2, "B", 1, "C_x", 3), r.ranks("", "kwh_per_job"));
        // regret on JCT: A 0, B 0.2, C 0.1; on kWh/job (best 4): A 0.25, B 0, C 0.5; weights 2 : 1
        Map<String, Double> sc = r.scores("");
        assertEquals(0.25 / 3, sc.get("A"), 1e-12);
        assertEquals(0.4 / 3, sc.get("B"), 1e-12);
        assertEquals(0.7 / 3, sc.get("C_x"), 1e-12);
        assertEquals(Set.of("A", "B"), r.pareto(""), "C is worse than A on both objectives");
    }

    @Test
    void stabilityVerdicts() throws Exception {
        DecisionReport r = new DecisionReport(cfg(REPORT), results(Map.of(), DATA));
        Map<String, DecisionReport.Stability> by = new LinkedHashMap<>();
        for (DecisionReport.Stability s : r.stability("")) by.put(s.metric(), s);
        // JCT: A leads by mean, but C beats it on seed 2; gaps C - A = 1, -1, 3
        DecisionReport.Stability jct = by.get("mean_jct_h");
        assertEquals("A", jct.leader());
        assertEquals("C_x", jct.runnerUp());
        assertEquals(2, jct.leaderSeedWins());
        assertEquals(1, jct.meanGap(), 1e-12);
        assertEquals("within seed noise", jct.verdict());
        // kWh/job: B best on every seed by exactly 1
        assertEquals("clear", by.get("kwh_per_job").verdict());
        assertEquals(3, by.get("kwh_per_job").leaderSeedWins());
        // p99: A best on every seed, but the gap (1, 1, 20) varies too much to pin down
        assertEquals("same order on every seed, size uncertain", by.get("p99_jct_h").verdict());
        assertTrue(by.containsKey(DecisionReport.SCORE));
    }

    @Test
    void oneSeedIsNotChecked() throws Exception {
        DecisionReport r = new DecisionReport(cfg(REPORT, "[1]"), results(Map.of(), DATA).stream().filter(x -> x.seed() == 1).toList());
        for (DecisionReport.Stability s : r.stability("")) assertEquals("one seed: not checked", s.verdict(), s.metric());
        assertTrue(Double.isNaN(r.stats("", "A", "mean_jct_h").ci95()));
    }

    @Test
    void missingRunsAreAnError() throws Exception {
        var runs = results(Map.of(), DATA).stream().filter(x -> x.seed() != 3).toList();
        var e = assertThrows(IllegalArgumentException.class, () -> new DecisionReport(cfg(REPORT), runs));
        assertTrue(e.getMessage().contains("missing from some runs"), e.getMessage());
    }

    @Test
    void leaderChangesAcrossSweepValuesAreFlagged() throws Exception {
        Map<String, double[][]> other = new LinkedHashMap<>(DATA);
        other.put("B", new double[][]{{8, 8, 8}, {2, 3, 30}, {4, 4, 4}});     // B now leads on JCT
        List<ExperimentRunner.RunResult> all = new ArrayList<>(results(Map.of("cpus", 24), DATA));
        all.addAll(results(Map.of("cpus", 32), other));
        DecisionReport r = new DecisionReport(cfg(REPORT), all);
        assertEquals(List.of("cpus=24", "cpus=32"), r.points());
        DecisionReport.SweepLeaders jct = r.sweepLeaders().stream().filter(l -> l.metric().equals("mean_jct_h")).findFirst().orElseThrow();
        assertEquals(List.of("A", "B"), jct.leaders());
        assertTrue(jct.changes());
        assertFalse(r.sweepLeaders().stream().filter(l -> l.metric().equals("kwh_per_job")).findFirst().orElseThrow().changes());
    }

    @Test
    void writesCsvAndCompilableLatex(@TempDir Path dir) throws Exception {
        List<ExperimentRunner.RunResult> all = new ArrayList<>(results(Map.of("cpus", 24), DATA));
        all.addAll(results(Map.of("cpus", 32), DATA));
        new DecisionReport(cfg(REPORT), all).write(dir);
        for (String f : List.of("runs.csv", "summary.csv", "ranking.csv", "stability.csv", "sweep_stability.csv", "decision-report.tex",
                "tables/metrics-1.tex", "tables/metrics-2.tex", "tables/ranking-1.tex", "tables/stability-2.tex", "tables/sweep.tex",
                "tables/assumptions.tex", "tables/candidates.tex"))
            assertTrue(Files.exists(dir.resolve(f)), f);
        assertEquals(1 + 2 * 3 * 3, Files.readAllLines(dir.resolve("runs.csv")).size());
        assertTrue(Files.readString(dir.resolve("runs.csv")).startsWith("point,candidate,seed,cpus,"));
        for (Path p : List.of(dir.resolve("decision-report.tex"), dir.resolve("tables/ranking-1.tex"), dir.resolve("tables/assumptions.tex"))) {
            String t = Files.readString(p).replace("\\{", "").replace("\\}", "");
            assertEquals(t.chars().filter(ch -> ch == '{').count(), t.chars().filter(ch -> ch == '}').count(), "balanced braces in " + p);
        }
        String doc = Files.readString(dir.resolve("decision-report.tex"));
        assertTrue(doc.contains("\\input{tables/metrics-2}") && doc.contains("\\input{tables/sweep}"));
        assertTrue(Files.readString(dir.resolve("tables/assumptions.tex")).contains("assume 100\\% \\& \\$5 \\{x\\}"), "escaped");
        assertTrue(Files.readString(dir.resolve("tables/ranking-1.tex")).contains("C\\_x"));
    }

    @Test
    void reportSettingsAreCheckedBeforeRunning() throws Exception {
        var e = assertThrows(IllegalArgumentException.class, () -> DecisionReport.validate(cfg("""
                report: { objectives: [ { metric: mean_jtc_h, weight: 1 } ] }
                """).report()));
        assertTrue(e.getMessage().contains("unknown metric 'mean_jtc_h'"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> DecisionReport.validate(cfg("""
                report: { objectives: [ { metric: gpu_alloc_util, weight: 1 } ] }
                """).report()));
        assertTrue(e.getMessage().contains("cannot be weighted"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> cfg("""
                report: { objectives: [ { metric: mean_jct_h } ] }
                """));
        assertTrue(e.getMessage().contains("weight is required"), e.getMessage());
    }

    @Test
    void latexEscaping() {
        assertEquals("a\\_b \\& 5\\% \\$ \\#1 \\{x\\} \\textasciitilde{}", DecisionReport.tex("a_b & 5% $ #1 {x} ~"));
    }
}
