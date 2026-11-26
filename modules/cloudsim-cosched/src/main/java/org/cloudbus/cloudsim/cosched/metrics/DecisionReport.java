package org.cloudbus.cloudsim.cosched.metrics;

import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig.Candidate;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig.Objective;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig.PluginSpec;
import org.cloudbus.cloudsim.cosched.config.Sweeps;
import org.cloudbus.cloudsim.cosched.metrics.MetricCatalog.Goal;
import org.cloudbus.cloudsim.cosched.metrics.MetricCatalog.Metric;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Turns all runs of a comparison into a decision report (architecture, L5): it ranks candidates per metric
 * and by the researcher's weights, shows trade-offs, and flags rankings that change across seeds or sweep
 * values, instead of naming a single winner. Every number is also written as CSV.
 *
 * <p><b>Weighted score.</b> For each objective, a candidate's <em>regret</em> is how much worse its mean is
 * than the best candidate's, relative to the best ({@code (x - best) / |best|} for lower-is-better metrics,
 * {@code (best - x) / |best|} for higher-is-better ones; when the best is 0 the gap is divided by the range
 * instead). The score is the weighted mean regret, so 0 means best on every objective and 0.10 means "10%
 * worse than the best, on weighted average". Relative regret keeps the size of a gap, which a rank does not.
 *
 * <p><b>Trade-offs.</b> A candidate is Pareto-optimal if no other candidate is at least as good on every
 * objective (every ranked metric shown, without objectives) and better on one.
 *
 * <p><b>Stability.</b> Candidates run on the same workload for a given seed, so differences are paired. For
 * each metric the report counts the seeds on which the leader (best mean) is also best, and tests the gap to
 * the runner-up with a paired 95% t-interval over seeds: "clear" if the interval excludes 0; "same order on
 * every seed, size uncertain" if the leader is best on every seed but the gap varies too much between seeds to
 * pin down; otherwise "within seed noise" (a tie at this number of seeds). Across sweep values it flags
 * metrics whose leader changes.
 */
public final class DecisionReport {

    public record Stats(int n, double mean, double sd, double ci95, double min, double max) {
    }

    /** One row of the stability check at one sweep point. {@code metric} is a metric name or "score". */
    public record Stability(String metric, String leader, int leaderSeedWins, int seeds, String runnerUp,
                            double meanGap, double ci95Gap, String verdict) {
    }

    /** Leader of one metric (or "score") at every sweep point. */
    public record SweepLeaders(String metric, List<String> leaders, boolean changes) {
    }

    public static final String SCORE = "score";

    private final ExperimentConfig cfg;
    private final List<String> points;                       // labels, in sweep order
    private final Map<String, Map<String, Object>> pointValues;
    private final List<String> candidates;
    private final List<Long> seeds;
    private final List<Metric> shown;
    private final List<Objective> objectives;
    private final Map<String, Double> values = new HashMap<>();  // point|cand|seed|metric
    private final List<String> assumptions;

    /**
     * @param cfg     the experiment (before sweeps), for candidates, seeds and the report settings
     * @param results every run, from {@link ComparisonRunner}
     */
    public DecisionReport(ExperimentConfig cfg, List<ExperimentRunner.RunResult> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("no runs to report on");
        this.cfg = cfg;
        this.candidates = cfg.candidates().stream().map(Candidate::name).toList();
        this.seeds = cfg.seeds();
        LinkedHashMap<String, Map<String, Object>> pv = new LinkedHashMap<>();
        Set<String> assume = new LinkedHashSet<>();
        for (ExperimentRunner.RunResult r : results) {
            String p = label(r.point());
            pv.putIfAbsent(p, r.point());
            assume.addAll(r.assumptions());
            for (Map.Entry<String, Object> e : r.summary().entrySet())
                if (e.getValue() instanceof Number n) values.put(key(p, r.candidate(), r.seed(), e.getKey()), n.doubleValue());
        }
        this.points = List.copyOf(pv.keySet());
        this.pointValues = pv;
        this.assumptions = List.copyOf(assume);

        List<String> names = cfg.report().metrics();
        List<Metric> s = new ArrayList<>();
        if (names == null) {
            for (Metric m : MetricCatalog.all()) if (presentEverywhere(m.name())) s.add(m);
        } else {
            for (String n : names) {
                Metric m = MetricCatalog.get(n);
                if (!presentEverywhere(n))
                    throw new IllegalArgumentException("report metric " + n + " is missing from some runs"
                            + (n.contains("energy") || n.contains("co2") || n.contains("sample") ? " (is energy modelled? add an energy section and power settings)" : ""));
                s.add(m);
            }
        }
        this.shown = List.copyOf(s);
        this.objectives = cfg.report().objectives();
        for (Objective o : objectives) {
            Metric m = MetricCatalog.get(o.metric());
            if (!m.ranked()) throw new IllegalArgumentException("objective " + o.metric() + " has no better direction; it cannot be weighted");
            if (!presentEverywhere(o.metric()))
                throw new IllegalArgumentException("objective " + o.metric() + " is missing from some runs (is energy modelled?)");
        }
    }

    /** Checks the report settings before any run: metric names exist and objectives can be ranked. */
    public static void validate(ExperimentConfig.Report report) {
        if (report.metrics() != null) for (String n : report.metrics()) MetricCatalog.get(n);
        for (Objective o : report.objectives())
            if (!MetricCatalog.get(o.metric()).ranked())
                throw new IllegalArgumentException("objective " + o.metric() + " has no better direction; it cannot be weighted");
    }

    // ---- computations -------------------------------------------------------------------------

    public List<String> points() {
        return points;
    }

    public List<Metric> shownMetrics() {
        return shown;
    }

    public double value(String point, String cand, long seed, String metric) {
        Double v = values.get(key(point, cand, seed, metric));
        if (v == null) throw new IllegalArgumentException("no value for " + metric + " of " + cand + " seed " + seed + " at '" + point + "'");
        return v;
    }

    public Stats stats(String point, String cand, String metric) {
        int n = seeds.size();
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = value(point, cand, seeds.get(i), metric);
        return stats(x);
    }

    static Stats stats(double[] x) {
        int n = x.length;
        double mean = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : x) {
            mean += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        mean /= n;
        double ss = 0;
        for (double v : x) ss += (v - mean) * (v - mean);
        double sd = n > 1 ? Math.sqrt(ss / (n - 1)) : 0;
        double ci = n > 1 ? tCritical(n - 1) * sd / Math.sqrt(n) : Double.NaN;
        return new Stats(n, mean, sd, ci, min, max);
    }

    /** Two-sided 95% Student t quantile. */
    static double tCritical(int df) {
        double[] t = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131,
                2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};
        if (df < 1) throw new IllegalArgumentException("df must be >= 1");
        return df <= 30 ? t[df - 1] : 1.96 + 2.4 / df;
    }

    /** Rank of each candidate on a metric by mean: 1 + the number of candidates strictly better. */
    public Map<String, Integer> ranks(String point, String metric) {
        Metric m = MetricCatalog.get(metric);
        Map<String, Integer> r = new LinkedHashMap<>();
        for (String c : candidates) {
            double x = stats(point, c, metric).mean();
            int better = 0;
            for (String o : candidates) if (m.better(stats(point, o, metric).mean(), x)) better++;
            r.put(c, 1 + better);
        }
        return r;
    }

    /** Weighted mean regret of each candidate at a point, from means; empty without objectives. */
    public Map<String, Double> scores(String point) {
        Map<String, Double> byCand = new LinkedHashMap<>();
        if (objectives.isEmpty()) return byCand;
        Map<String, Map<String, Double>> means = new HashMap<>();
        for (Objective o : objectives) {
            Map<String, Double> mm = new LinkedHashMap<>();
            for (String c : candidates) mm.put(c, stats(point, c, o.metric()).mean());
            means.put(o.metric(), mm);
        }
        return weightedRegret(means);
    }

    /** Weighted mean regret from one value per candidate per objective metric. */
    Map<String, Double> weightedRegret(Map<String, Map<String, Double>> byMetric) {
        double wSum = objectives.stream().mapToDouble(Objective::weight).sum();
        Map<String, Double> score = new LinkedHashMap<>();
        for (String c : candidates) score.put(c, 0.0);
        for (Objective o : objectives) {
            Metric m = MetricCatalog.get(o.metric());
            Map<String, Double> x = byMetric.get(o.metric());
            double best = x.values().stream().reduce((a, b) -> m.better(a, b) ? a : b).orElseThrow();
            double worst = x.values().stream().reduce((a, b) -> m.better(a, b) ? b : a).orElseThrow();
            for (String c : candidates) {
                double gap = Math.abs(x.get(c) - best);
                double reg = Math.abs(best) > 1e-12 ? gap / Math.abs(best)
                        : Math.abs(worst - best) > 1e-12 ? gap / Math.abs(worst - best) : 0;
                score.merge(c, o.weight() / wSum * reg, Double::sum);
            }
        }
        return score;
    }

    /** Weighted regret of each candidate on one seed (that seed's values). */
    Map<String, Double> seedScores(String point, long seed) {
        Map<String, Map<String, Double>> v = new HashMap<>();
        for (Objective o : objectives) {
            Map<String, Double> mm = new LinkedHashMap<>();
            for (String c : candidates) mm.put(c, value(point, c, seed, o.metric()));
            v.put(o.metric(), mm);
        }
        return weightedRegret(v);
    }

    /** Candidates no other candidate dominates on the objectives (on every ranked metric shown, without objectives). */
    public Set<String> pareto(String point) {
        List<Metric> dims = new ArrayList<>();
        if (!objectives.isEmpty()) for (Objective o : objectives) dims.add(MetricCatalog.get(o.metric()));
        else for (Metric m : shown) if (m.ranked()) dims.add(m);
        Set<String> front = new LinkedHashSet<>();
        for (String c : candidates) {
            boolean dominated = false;
            for (String o : candidates) {
                if (o.equals(c)) continue;
                boolean allAsGood = true, oneBetter = false;
                for (Metric m : dims) {
                    double a = stats(point, o, m.name()).mean(), b = stats(point, c, m.name()).mean();
                    if (m.better(b, a)) allAsGood = false;
                    if (m.better(a, b)) oneBetter = true;
                }
                if (allAsGood && oneBetter) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) front.add(c);
        }
        return front;
    }

    /** Stability of each ranked metric shown, and of the weighted score, at one point. */
    public List<Stability> stability(String point) {
        List<Stability> rows = new ArrayList<>();
        for (Metric m : shown) {
            if (!m.ranked()) continue;
            Map<Long, Map<String, Double>> perSeed = new LinkedHashMap<>();
            for (long s : seeds) {
                Map<String, Double> v = new LinkedHashMap<>();
                for (String c : candidates) v.put(c, value(point, c, s, m.name()));
                perSeed.put(s, v);
            }
            Map<String, Double> means = new LinkedHashMap<>();
            for (String c : candidates) means.put(c, stats(point, c, m.name()).mean());
            rows.add(stabilityRow(m.name(), m.goal(), means, perSeed));
        }
        if (!objectives.isEmpty()) {
            Map<Long, Map<String, Double>> perSeed = new LinkedHashMap<>();
            for (long s : seeds) perSeed.put(s, seedScores(point, s));
            rows.add(stabilityRow(SCORE, Goal.MIN, scores(point), perSeed));
        }
        return rows;
    }

    private Stability stabilityRow(String metric, Goal goal, Map<String, Double> means, Map<Long, Map<String, Double>> perSeed) {
        List<String> order = new ArrayList<>(candidates);
        order.sort((a, b) -> goal == Goal.MIN ? Double.compare(means.get(a), means.get(b)) : Double.compare(means.get(b), means.get(a)));
        String leader = order.get(0);
        if (order.size() < 2) return new Stability(metric, leader, seeds.size(), seeds.size(), "", Double.NaN, Double.NaN, "one candidate");
        String runner = order.get(1);
        int wins = 0;
        double[] gaps = new double[seeds.size()];
        int i = 0;
        for (Map<String, Double> v : perSeed.values()) {
            double l = v.get(leader);
            boolean best = true;
            for (double o : v.values()) if (goal == Goal.MIN ? o < l : o > l) best = false;
            if (best) wins++;
            gaps[i++] = goal == Goal.MIN ? v.get(runner) - l : l - v.get(runner);
        }
        Stats g = stats(gaps);
        String verdict;
        if (seeds.size() < 2) verdict = "one seed: not checked";
        else if (g.mean() - g.ci95() > 0) verdict = "clear";
        else if (wins == seeds.size()) verdict = "same order on every seed, size uncertain";
        else verdict = "within seed noise";
        return new Stability(metric, leader, wins, seeds.size(), runner, g.mean(), g.ci95(), verdict);
    }

    /** Leader of each ranked metric (and the score) at every sweep point; empty with a single point. */
    public List<SweepLeaders> sweepLeaders() {
        List<SweepLeaders> out = new ArrayList<>();
        if (points.size() < 2) return out;
        List<String> metrics = new ArrayList<>();
        for (Metric m : shown) if (m.ranked()) metrics.add(m.name());
        if (!objectives.isEmpty()) metrics.add(SCORE);
        for (String metric : metrics) {
            List<String> leaders = new ArrayList<>();
            for (String p : points) {
                for (Stability s : stability(p)) if (s.metric().equals(metric)) leaders.add(s.leader());
            }
            out.add(new SweepLeaders(metric, leaders, leaders.stream().distinct().count() > 1));
        }
        return out;
    }

    // ---- output ---------------------------------------------------------------------------------

    /** Writes the CSV files, the LaTeX tables and a standalone {@code decision-report.tex} into {@code dir}. */
    public void write(Path dir) {
        try {
            Files.createDirectories(dir.resolve("tables"));
            Files.writeString(dir.resolve("runs.csv"), runsCsv());
            Files.writeString(dir.resolve("summary.csv"), summaryCsv());
            Files.writeString(dir.resolve("ranking.csv"), rankingCsv());
            Files.writeString(dir.resolve("stability.csv"), stabilityCsv());
            if (points.size() > 1) Files.writeString(dir.resolve("sweep_stability.csv"), sweepCsv());
            Files.writeString(dir.resolve("tables/candidates.tex"), candidatesTex());
            for (int i = 0; i < points.size(); i++) {
                String p = points.get(i);
                Files.writeString(dir.resolve("tables/metrics-" + (i + 1) + ".tex"), metricsTex(p));
                Files.writeString(dir.resolve("tables/ranking-" + (i + 1) + ".tex"), rankingTex(p));
                Files.writeString(dir.resolve("tables/stability-" + (i + 1) + ".tex"), stabilityTex(p));
            }
            if (points.size() > 1) Files.writeString(dir.resolve("tables/sweep.tex"), sweepTex());
            Files.writeString(dir.resolve("tables/assumptions.tex"), assumptionsTex());
            Files.writeString(dir.resolve("decision-report.tex"), documentTex());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Short console summary: per point, the leader of each objective and the score ranking. */
    public String consoleSummary() {
        StringBuilder sb = new StringBuilder();
        for (String p : points) {
            sb.append(p.isEmpty() ? "all runs" : p).append(":\n");
            Map<String, Double> sc = scores(p);
            for (Stability s : stability(p)) {
                String name = s.metric().equals(SCORE) ? "weighted score" : MetricCatalog.get(s.metric()).label().replace("$_2$", "2");
                sb.append(String.format(Locale.ROOT, "  %-20s leader %-16s (%d/%d seeds, %s)%n", name, s.leader(), s.leaderSeedWins(),
                        s.seeds(), s.verdict()));
            }
            if (!sc.isEmpty()) {
                StringJoiner j = new StringJoiner(", ");
                sc.entrySet().stream().sorted(Map.Entry.comparingByValue())
                        .forEach(e -> j.add(e.getKey() + String.format(Locale.ROOT, " %.1f%%", 100 * e.getValue())));
                sb.append("  score (weighted regret): ").append(j).append('\n');
            }
            sb.append("  Pareto-optimal: ").append(pareto(p)).append('\n');
        }
        return sb.toString();
    }

    private String runsCsv() {
        StringBuilder sb = new StringBuilder("point,candidate,seed");
        List<String> sweepNames = sweepNames();
        for (String s : sweepNames) sb.append(',').append(s);
        List<String> metricNames = allMetricNames();
        for (String m : metricNames) sb.append(',').append(m);
        sb.append('\n');
        for (String p : points)
            for (String c : candidates)
                for (long s : seeds) {
                    sb.append(csv(p)).append(',').append(csv(c)).append(',').append(s);
                    for (String sn : sweepNames) sb.append(',').append(csv(String.valueOf(pointValues.get(p).get(sn))));
                    for (String m : metricNames) {
                        Double v = values.get(key(p, c, s, m));
                        sb.append(',').append(v == null ? "" : Double.toString(v));
                    }
                    sb.append('\n');
                }
        return sb.toString();
    }

    private String summaryCsv() {
        StringBuilder sb = new StringBuilder("point,candidate,metric,n,mean,sd,ci95,min,max\n");
        for (String p : points)
            for (String c : candidates)
                for (Metric m : shown) {
                    Stats s = stats(p, c, m.name());
                    sb.append(csv(p)).append(',').append(csv(c)).append(',').append(m.name()).append(',').append(s.n());
                    for (double d : new double[]{s.mean(), s.sd(), s.ci95(), s.min(), s.max()}) sb.append(',').append(g(d));
                    sb.append('\n');
                }
        return sb.toString();
    }

    private String rankingCsv() {
        StringBuilder sb = new StringBuilder("point,candidate");
        for (Metric m : shown) if (m.ranked()) sb.append(',').append(m.name()).append("_rank");
        if (!objectives.isEmpty()) sb.append(",score,score_rank,score_seed_wins");
        sb.append(",pareto\n");
        for (String p : points) {
            Map<String, Map<String, Integer>> ranks = new HashMap<>();
            for (Metric m : shown) if (m.ranked()) ranks.put(m.name(), ranks(p, m.name()));
            Map<String, Double> sc = scores(p);
            Map<String, Integer> scRank = rankScores(sc);
            Map<String, Integer> wins = seedWins(p);
            Set<String> front = pareto(p);
            for (String c : candidates) {
                sb.append(csv(p)).append(',').append(csv(c));
                for (Metric m : shown) if (m.ranked()) sb.append(',').append(ranks.get(m.name()).get(c));
                if (!objectives.isEmpty()) sb.append(',').append(g(sc.get(c))).append(',').append(scRank.get(c)).append(',').append(wins.get(c));
                sb.append(',').append(front.contains(c)).append('\n');
            }
        }
        return sb.toString();
    }

    private String stabilityCsv() {
        StringBuilder sb = new StringBuilder("point,metric,leader,leader_seed_wins,seeds,runner_up,mean_gap,ci95_gap,verdict\n");
        for (String p : points)
            for (Stability s : stability(p))
                sb.append(csv(p)).append(',').append(s.metric()).append(',').append(csv(s.leader())).append(',').append(s.leaderSeedWins())
                        .append(',').append(s.seeds()).append(',').append(csv(s.runnerUp())).append(',').append(g(s.meanGap()))
                        .append(',').append(g(s.ci95Gap())).append(',').append(csv(s.verdict())).append('\n');
        return sb.toString();
    }

    private String sweepCsv() {
        StringBuilder sb = new StringBuilder("metric");
        for (String p : points) sb.append(',').append(csv(p));
        sb.append(",leader_changes\n");
        for (SweepLeaders l : sweepLeaders()) {
            sb.append(l.metric());
            for (String x : l.leaders()) sb.append(',').append(csv(x));
            sb.append(',').append(l.changes()).append('\n');
        }
        return sb.toString();
    }

    private Map<String, Integer> rankScores(Map<String, Double> sc) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (String c : sc.keySet()) {
            int better = 0;
            for (double o : sc.values()) if (o < sc.get(c) - 1e-12) better++;
            r.put(c, 1 + better);
        }
        return r;
    }

    /** Seeds on which each candidate has the lowest weighted score. */
    private Map<String, Integer> seedWins(String p) {
        Map<String, Integer> w = new LinkedHashMap<>();
        for (String c : candidates) w.put(c, 0);
        if (objectives.isEmpty()) return w;
        for (long s : seeds) {
            Map<String, Double> sc = seedScores(p, s);
            double best = sc.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            for (String c : candidates) if (sc.get(c) <= best + 1e-12) w.merge(c, 1, Integer::sum);
        }
        return w;
    }

    // ---- LaTeX ----------------------------------------------------------------------------------

    private String metricsTex(String p) {
        boolean wide = candidates.size() > 4;
        StringBuilder sb = new StringBuilder(header("metrics", p));
        sb.append("\\begin{table}[ht]\n\\centering\\small\n");
        sb.append("\\caption{").append(tex(pointTitle(p))).append(": mean over ").append(seeds.size())
                .append(seeds.size() > 1 ? " seeds $\\pm$ 95\\% confidence interval" : " seed").append(". Best in bold; ")
                .append("$\\downarrow$ lower is better, $\\uparrow$ higher is better.}\n");
        if (wide) sb.append("\\resizebox{\\linewidth}{!}{%\n");
        sb.append("\\begin{tabular}{@{}llc").append("r".repeat(candidates.size())).append("@{}}\n\\toprule\n");
        sb.append("Metric & Unit & ");
        for (String c : candidates) sb.append(" & ").append(tex(c));
        sb.append(" \\\\\n\\midrule\n");
        for (Metric m : shown) {
            Map<String, Integer> rk = m.ranked() ? ranks(p, m.name()) : Map.of();
            sb.append(m.label()).append(" & ").append(texUnit(m.unit())).append(" & ")
                    .append(m.goal() == Goal.MIN ? "$\\downarrow$" : m.goal() == Goal.MAX ? "$\\uparrow$" : "");
            for (String c : candidates) {
                Stats s = stats(p, c, m.name());
                String v = fmt(m, s.mean());
                if (m.ranked() && rk.get(c) == 1 && candidates.size() > 1) v = "\\textbf{" + v + "}";
                if (s.n() > 1) v += " $\\pm$ " + fmt(m, s.ci95());
                sb.append(" & ").append(v);
            }
            sb.append(" \\\\\n");
        }
        sb.append("\\bottomrule\n\\end{tabular}").append(wide ? "}" : "").append("\n\\end{table}\n");
        return sb.toString();
    }

    private String rankingTex(String p) {
        StringBuilder sb = new StringBuilder(header("ranking", p));
        Map<String, Double> sc = scores(p);
        Map<String, Integer> scRank = rankScores(sc);
        Map<String, Integer> wins = seedWins(p);
        Set<String> front = pareto(p);
        sb.append("\\begin{table}[ht]\n\\centering\\small\n\\caption{").append(tex(pointTitle(p))).append(": ");
        if (!objectives.isEmpty())
            sb.append("weighted score = weighted mean regret over the objectives (0\\% = best on every objective); ");
        sb.append("Pareto-optimal = no other candidate is at least as good on every ")
                .append(objectives.isEmpty() ? "ranked metric" : "objective").append(" and better on one.}\n");
        sb.append("\\begin{tabular}{@{}l").append(objectives.isEmpty() ? "" : "rrr").append("cp{0.42\\linewidth}@{}}\n\\toprule\n");
        sb.append("Candidate");
        if (!objectives.isEmpty()) sb.append(" & Score & Rank & Seeds won");
        sb.append(" & Pareto & Best on \\\\\n\\midrule\n");
        List<String> order = new ArrayList<>(candidates);
        if (!objectives.isEmpty()) order.sort((a, b) -> Double.compare(sc.get(a), sc.get(b)));
        for (String c : order) {
            sb.append(tex(c));
            if (!objectives.isEmpty())
                sb.append(String.format(Locale.ROOT, " & %.1f\\%% & %d & %d/%d", 100 * sc.get(c), scRank.get(c), wins.get(c), seeds.size()));
            sb.append(" & ").append(front.contains(c) ? "yes" : "no").append(" & ");
            StringJoiner best = new StringJoiner(", ");
            if (candidates.size() > 1)
                for (Metric m : shown) if (m.ranked() && ranks(p, m.name()).get(c) == 1) best.add(m.label());
            sb.append(best.length() == 0 ? "--" : best.toString()).append(" \\\\\n");
        }
        sb.append("\\bottomrule\n\\end{tabular}\n\\end{table}\n");
        if (!objectives.isEmpty()) {
            sb.append("% Objectives and weights: ");
            for (Objective o : objectives) sb.append(o.metric()).append('=').append(o.weight()).append(' ');
            sb.append('\n');
        }
        return sb.toString();
    }

    private String stabilityTex(String p) {
        StringBuilder sb = new StringBuilder(header("stability", p));
        sb.append("\\begin{table}[ht]\n\\centering\\small\n\\caption{").append(tex(pointTitle(p)))
                .append(": how stable each leader is. Seeds led = seeds on which the leader is also best. Gap = runner-up minus leader, "
                        + "in the leader's favour, mean over seeds $\\pm$ paired 95\\% interval; \\emph{clear} if the interval excludes 0; "
                        + "\\emph{same order on every seed} if the leader wins every seed but the gap's size varies too much to pin down; "
                        + "otherwise a tie at this number of seeds.}\n");
        sb.append("\\begin{tabular}{@{}llcllp{0.2\\linewidth}@{}}\n\\toprule\n");
        sb.append("Metric & Leader & Seeds led & Runner-up & Gap & Verdict \\\\\n\\midrule\n");
        for (Stability s : stability(p)) {
            boolean score = s.metric().equals(SCORE);
            Metric m = score ? null : MetricCatalog.get(s.metric());
            String gap;
            if (Double.isNaN(s.meanGap())) gap = "--";
            else if (score) gap = String.format(Locale.ROOT, "%.1f", 100 * s.meanGap())
                    + (Double.isNaN(s.ci95Gap()) ? "" : String.format(Locale.ROOT, " $\\pm$ %.1f", 100 * s.ci95Gap())) + " pts";
            else gap = fmt(m, s.meanGap()) + (Double.isNaN(s.ci95Gap()) ? "" : " $\\pm$ " + fmt(m, s.ci95Gap())) + " " + texUnit(m.unit());
            sb.append(score ? "\\emph{Weighted score}" : m.label()).append(" & ").append(tex(s.leader())).append(" & ")
                    .append(s.leaderSeedWins()).append('/').append(s.seeds()).append(" & ").append(tex(s.runnerUp())).append(" & ")
                    .append(gap).append(" & ").append(s.verdict().equals("clear") ? "clear" : "\\textbf{" + tex(s.verdict()) + "}")
                    .append(" \\\\\n");
        }
        sb.append("\\bottomrule\n\\end{tabular}\n\\end{table}\n");
        return sb.toString();
    }

    private String sweepTex() {
        StringBuilder sb = new StringBuilder(header("sweep", ""));
        boolean wide = points.size() > 4;
        sb.append("\\begin{table}[ht]\n\\centering\\small\n\\caption{Leader of each metric at every sweep value. "
                + "\\textbf{Changes} marks metrics whose best candidate depends on the sweep value: the answer to the "
                + "what-if question is then ``it depends''.}\n");
        if (wide) sb.append("\\resizebox{\\linewidth}{!}{%\n");
        sb.append("\\begin{tabular}{@{}l").append("l".repeat(points.size())).append("c@{}}\n\\toprule\nMetric");
        for (String p : points) sb.append(" & ").append(tex(pointTitle(p)));
        sb.append(" & Leader \\\\\n\\midrule\n");
        for (SweepLeaders l : sweepLeaders()) {
            sb.append(l.metric().equals(SCORE) ? "\\emph{Weighted score}" : MetricCatalog.get(l.metric()).label());
            for (String x : l.leaders()) sb.append(" & ").append(tex(x));
            sb.append(" & ").append(l.changes() ? "\\textbf{changes}" : "stable").append(" \\\\\n");
        }
        sb.append("\\bottomrule\n\\end{tabular}").append(wide ? "}" : "").append("\n\\end{table}\n");
        return sb.toString();
    }

    private String candidatesTex() {
        StringBuilder sb = new StringBuilder(header("candidates", ""));
        sb.append("\\begin{table}[ht]\n\\centering\\small\n\\caption{The candidate schedulers. Parameters not shown take their "
                + "documented defaults (see each run's manifest.json).}\n");
        sb.append("\\begin{tabular}{@{}lp{0.75\\linewidth}@{}}\n\\toprule\nCandidate & Plug-ins \\\\\n\\midrule\n");
        for (Candidate c : cfg.candidates()) {
            sb.append(tex(c.name())).append(" & ").append(tex("ordering " + plugin(c.ordering()) + "; allocation " + plugin(c.allocation())
                    + "; placement " + plugin(c.placement()) + "; lifecycle " + plugin(c.lifecycle()) + "; trigger " + plugin(c.trigger())))
                    .append(" \\\\\n");
        }
        sb.append("\\bottomrule\n\\end{tabular}\n\\end{table}\n");
        return sb.toString();
    }

    private static String plugin(PluginSpec p) {
        return p.params().isEmpty() ? p.name() : p.name() + " " + p.params();
    }

    private String assumptionsTex() {
        StringBuilder sb = new StringBuilder(header("assumptions", ""));
        sb.append("\\begin{itemize}\n");
        for (String a : assumptions) sb.append("  \\item ").append(tex(a)).append('\n');
        sb.append("\\end{itemize}\n");
        return sb.toString();
    }

    private String documentTex() {
        StringBuilder sb = new StringBuilder();
        sb.append("% Decision report generated by the CPU-GPU co-scheduling module (DecisionReport). Compile with pdflatex.\n");
        sb.append("\\documentclass[11pt,a4paper]{article}\n\\usepackage[margin=2cm]{geometry}\n\\usepackage[T1]{fontenc}\n")
                .append("\\usepackage{lmodern}\n\\usepackage{booktabs}\n\\usepackage{graphicx}\n\\usepackage{array}\n\n");
        sb.append("\\title{Decision report: ").append(tex(cfg.name())).append("}\n\\date{}\n\n\\begin{document}\n\\maketitle\n\n");
        sb.append("\\section*{How to read this report}\n");
        sb.append("Every candidate ran on the same cluster and the same workload for each of ").append(seeds.size())
                .append(seeds.size() == 1 ? " seed" : " seeds").append(" (").append(tex(seeds.toString())).append(")");
        if (points.size() > 1) sb.append(", at each of ").append(points.size()).append(" sweep values");
        sb.append(". The report does not name a single winner: it ranks candidates per metric");
        if (!objectives.isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            for (Objective o : objectives) j.add(MetricCatalog.get(o.metric()).label() + " (weight " + g(o.weight()) + ")");
            sb.append(" and by the weighted score over ").append(j);   // labels are LaTeX already
        }
        sb.append(", shows which candidates are trade-offs (Pareto-optimal), and says whether each lead is clear or within the "
                + "noise between seeds. Read the assumptions before acting on a ranking.\n\n");
        sb.append("\\input{tables/candidates}\n\n");
        for (int i = 0; i < points.size(); i++) {
            String p = points.get(i);
            sb.append("\\clearpage\n\\section{").append(tex(pointTitle(p))).append("}\n");
            sb.append("\\input{tables/metrics-").append(i + 1).append("}\n\\input{tables/ranking-").append(i + 1)
                    .append("}\n\\input{tables/stability-").append(i + 1).append("}\n\n");
        }
        if (points.size() > 1) sb.append("\\clearpage\n\\section{Across sweep values}\n\\input{tables/sweep}\n\n");
        sb.append("\\section{Assumptions}\n\\input{tables/assumptions}\n\n\\section{Metrics}\n\\begin{description}\n");
        for (Metric m : shown) sb.append("  \\item[").append(m.label()).append("] ").append(tex(m.meaning())).append(".\n");
        sb.append("\\end{description}\n\n\\end{document}\n");
        return sb.toString();
    }

    private static String header(String what, String point) {
        return "% " + what + (point.isEmpty() ? "" : " at " + point) + "; generated by DecisionReport. Needs booktabs (and graphicx for wide tables).\n";
    }

    /** Title of a point: its sweep values, e.g. "cpus = 32, jobs_per_hour = 9". */
    private String pointTitle(String p) {
        if (p.isEmpty()) return "All runs";
        StringJoiner j = new StringJoiner(", ");
        for (Map.Entry<String, Object> e : pointValues.get(p).entrySet()) j.add(e.getKey() + " = " + e.getValue());
        return j.toString();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private boolean presentEverywhere(String metric) {
        for (String p : points)
            for (String c : candidates)
                for (long s : seeds) {
                    Double v = values.get(key(p, c, s, metric));
                    if (v == null || v.isNaN()) return false;
                }
        return true;
    }

    private List<String> allMetricNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Metric m : MetricCatalog.all()) if (presentAnywhere(m.name())) names.add(m.name());
        for (String k : values.keySet()) names.add(k.substring(k.lastIndexOf('|') + 1));
        return new ArrayList<>(names);
    }

    private boolean presentAnywhere(String metric) {
        for (String k : values.keySet()) if (k.endsWith("|" + metric)) return true;
        return false;
    }

    private List<String> sweepNames() {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> v : pointValues.values()) s.addAll(v.keySet());
        return new ArrayList<>(s);
    }

    /** Same label as the comparison runner's folder for a sweep point. */
    static String label(Map<String, Object> point) {
        return Sweeps.labelOf(point);
    }

    private static String key(String point, String cand, long seed, String metric) {
        return point + "|" + cand + "|" + seed + "|" + metric;
    }

    static String fmt(Metric m, double v) {
        if (Double.isNaN(v)) return "--";
        return String.format(Locale.ROOT, "%." + m.digits() + "f", v * m.scale());
    }

    private static String g(double d) {
        return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.6g", d);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String texUnit(String u) {
        return u.equals("%") ? "\\%" : u;
    }

    /** Escapes LaTeX special characters in plain text. */
    static String tex(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> b.append("\\textbackslash{}");
                case '&', '%', '$', '#', '_', '{', '}' -> b.append('\\').append(ch);
                case '~' -> b.append("\\textasciitilde{}");
                case '^' -> b.append("\\textasciicircum{}");
                case '<' -> b.append("\\textless{}");
                case '>' -> b.append("\\textgreater{}");
                default -> b.append(ch);
            }
        }
        return b.toString();
    }
}
