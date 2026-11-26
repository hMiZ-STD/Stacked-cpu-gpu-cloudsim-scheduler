package org.cloudbus.cloudsim.cosched.metrics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.cloudbus.cloudsim.cosched.config.Sweeps;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a whole comparison (architecture, L5): every candidate x seed x sweep value, then writes the decision
 * report. Every sweep point is validated before the first run, so a wrong path or value fails at once rather
 * than after an hour of simulation.
 *
 * <p>Output: runs under {@code <output_dir>/<candidate>/seed-<seed>/} (without sweeps) or
 * {@code <output_dir>/<sweep point>/<candidate>/seed-<seed>/}, and the report in {@code <output_dir>/report/}.
 * Runs are sequential: CloudSim keeps its simulation state in static fields, so two runs cannot share a JVM
 * at the same time.
 *
 * <pre>java ... org.cloudbus.cloudsim.cosched.metrics.ComparisonRunner presets/decision-demo/decision-demo.yaml</pre>
 */
public final class ComparisonRunner {

    /** Everything a comparison produced. */
    public record Result(List<ExperimentRunner.RunResult> runs, DecisionReport report, Path reportDir) {
    }

    private ComparisonRunner() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ComparisonRunner <experiment.yaml>");
            System.exit(1);
        }
        Result r;
        try {
            r = run(Path.of(args[0]), true);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());   // a mistake in the YAML or its inputs: no stack trace
            System.exit(2);
            return;
        }
        System.out.println(r.report().consoleSummary());
        System.out.println("decision report: " + r.reportDir().resolve("decision-report.tex"));
    }

    public static Result run(Path yaml) {
        return run(yaml, false);
    }

    static Result run(Path yaml, boolean progress) {
        ObjectNode tree = ExperimentConfig.readTree(yaml);
        ExperimentConfig base = ExperimentConfig.bind(tree, yaml.toString());
        DecisionReport.validate(base.report());
        List<Sweeps.Point> points = Sweeps.expand(tree, base.sweeps());
        List<ExperimentConfig> configs = new ArrayList<>();
        for (Sweeps.Point p : points)
            configs.add(ExperimentConfig.bind(p.tree(), yaml + (p.label().isEmpty() ? "" : " at sweep point " + p.label())));
        Path out = ExperimentConfig.resolve(yaml, base.outputDir());
        int total = points.size() * base.candidates().size() * base.seeds().size();
        List<ExperimentRunner.RunResult> runs = new ArrayList<>();
        Instant start = Instant.now();
        for (int i = 0; i < points.size(); i++) {
            Sweeps.Point p = points.get(i);
            ExperimentConfig c = configs.get(i);
            Path dir = p.label().isEmpty() ? out : out.resolve(p.label());
            for (ExperimentConfig.Candidate cand : c.candidates())
                for (long seed : c.seeds()) {
                    ExperimentRunner.RunResult r = ExperimentRunner.runOne(yaml, c, cand, seed,
                            dir.resolve(cand.name()).resolve("seed-" + seed), p.values());
                    runs.add(r);
                    if (progress)
                        System.err.printf("[%d/%d] %s%s seed %d done (%ds elapsed)%n", runs.size(), total,
                                p.label().isEmpty() ? "" : p.label() + " ", cand.name(), seed,
                                Duration.between(start, Instant.now()).toSeconds());
                }
        }
        DecisionReport report = new DecisionReport(base, runs);
        Path reportDir = out.resolve("report");
        report.write(reportDir);
        return new Result(runs, report, reportDir);
    }
}
