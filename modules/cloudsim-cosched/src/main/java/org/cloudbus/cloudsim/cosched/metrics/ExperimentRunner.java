package org.cloudbus.cloudsim.cosched.metrics;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.cosched.StandardPlugins;
import org.cloudbus.cloudsim.cosched.broker.CoSchedulingBroker;
import org.cloudbus.cloudsim.cosched.broker.SchedulerSetup;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig.Candidate;
import org.cloudbus.cloudsim.cosched.config.ExperimentConfig.PluginSpec;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.Params;
import org.cloudbus.cloudsim.cosched.core.PolicyRegistry;
import org.cloudbus.cloudsim.cosched.core.PowerSettings;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.InterferenceModel;
import org.cloudbus.cloudsim.cosched.model.LocalityModel;
import org.cloudbus.cloudsim.cosched.model.Profile;
import org.cloudbus.cloudsim.cosched.model.ProfileRepository;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;
import org.cloudbus.cloudsim.cosched.model.RateCalculator;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.JointAllocationPlacement;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;
import org.cloudbus.cloudsim.cosched.workload.JobCsvWriter;
import org.cloudbus.cloudsim.cosched.workload.JobRecord;
import org.cloudbus.cloudsim.cosched.workload.ModelLabeler;
import org.cloudbus.cloudsim.cosched.workload.TraceWorkload;
import org.cloudbus.cloudsim.cosched.workload.WorkloadGenerator;
import org.cloudbus.cloudsim.gpu.CloudSimGpuLayer;
import org.cloudbus.cloudsim.gpu.api.PowerSpec;
import org.cloudbus.cloudsim.gpu.api.ServerShape;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs one experiment: every candidate x seed, each in its own fresh simulation. Output per run:
 * {@code <output_dir>/<candidate>/seed-<seed>/} with {@code jobs.csv}, {@code timeline.csv},
 * {@code manifest.json} and, for a generated workload, {@code workload.csv}. Sweeps and the decision
 * report are {@link ComparisonRunner}'s job; this class's {@link #main} hands over to it.
 *
 * <pre>java ... org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner presets/smoke/smoke.yaml</pre>
 */
public final class ExperimentRunner {

    /**
     * Result of one run.
     *
     * @param point       the sweep values of this run (empty without sweeps)
     * @param assumptions the run's modelling assumptions and data sources, in words
     */
    public record RunResult(String candidate, long seed, Path outputDir, Map<String, Object> summary,
                            Map<String, Object> point, List<String> assumptions) {
    }

    private ExperimentRunner() {
    }

    public static void main(String[] args) {
        ComparisonRunner.main(args);
    }

    /** Runs every candidate x seed of an experiment without sweeps (use {@link ComparisonRunner} for those). */
    public static List<RunResult> run(Path yaml) {
        ExperimentConfig cfg = ExperimentConfig.load(yaml);
        if (!cfg.sweeps().isEmpty())
            throw new IllegalArgumentException(yaml + " has sweeps: run it with ComparisonRunner");
        return runAll(yaml, cfg, ExperimentConfig.resolve(yaml, cfg.outputDir()), Map.of());
    }

    /** Every candidate x seed of one (already swept) configuration, with output under {@code outBase}. */
    static List<RunResult> runAll(Path yaml, ExperimentConfig cfg, Path outBase, Map<String, Object> point) {
        List<RunResult> results = new ArrayList<>();
        for (Candidate c : cfg.candidates())
            for (long seed : cfg.seeds())
                results.add(runOne(yaml, cfg, c, seed, outBase.resolve(c.name()).resolve("seed-" + seed), point));
        return results;
    }

    static RunResult runOne(Path yaml, ExperimentConfig cfg, Candidate cand, long seed, Path out, Map<String, Object> point) {
        Instant started = Instant.now();
        PolicyRegistry reg = StandardPlugins.registry();
        Map<String, Object> plugins = new LinkedHashMap<>();
        SchedulingTrigger trigger = create(reg, SchedulingTrigger.class, "trigger", cand, cand.trigger(), plugins);
        OrderingPolicy ordering = create(reg, OrderingPolicy.class, "ordering", cand, cand.ordering(), plugins);
        AllocationPolicy allocation = null;
        JointAllocationPlacement joint = null;
        if (reg.has(JointAllocationPlacement.class, cand.allocation().name()))
            joint = create(reg, JointAllocationPlacement.class, "allocation", cand, cand.allocation(), plugins);
        else
            allocation = create(reg, AllocationPolicy.class, "allocation", cand, cand.allocation(), plugins);
        SchedulerSetup setup = new SchedulerSetup(trigger, ordering, allocation, joint,
                create(reg, PlacementPolicy.class, "placement", cand, cand.placement(), plugins),
                create(reg, LifecycleController.class, "lifecycle", cand, cand.lifecycle(), plugins));

        // Workload: a fixed job CSV, or generated from this run's seed (then kept as workload.csv for replay).
        Map<String, Object> workloadInfo = new LinkedHashMap<>();
        ModelLabeler labeller = create(reg, ModelLabeler.class, "labeller", cand, cfg.workload().labeller(), workloadInfo);
        TraceWorkload workload;
        Path csv;
        if (cfg.workload().csv() != null) {
            csv = ExperimentConfig.resolve(yaml, cfg.workload().csv());
            workload = TraceWorkload.fromCsv(csv, labeller);
        } else {
            WorkloadGenerator gen = create(reg, WorkloadGenerator.class, "generator", cand, cfg.workload().generator(), workloadInfo);
            workload = new TraceWorkload(gen.generate(seed, yaml.toAbsolutePath().getParent()), labeller);
            csv = out.resolve("workload.csv");
            JobCsvWriter.write(csv, workload.jobs());
        }

        Path profilesDir = ExperimentConfig.resolve(yaml, cfg.models().profilesDir());
        ProfileRepository profiles = ProfileRepository.load(profilesDir, ProfileRepository.Scaling.parse(cfg.models().scaling()));
        ClusterSpec spec = cfg.cluster();
        Set<String> models = new TreeSet<>();
        Set<Integer> gpuCounts = new TreeSet<>();
        for (JobRecord j : workload.jobs()) {
            models.add(j.model());
            gpuCounts.add(j.gpus());
        }
        profiles.requireAll(models, spec.gpuTypes(), gpuCounts);
        ProfileTableModel throughput = new ProfileTableModel(profiles, ProfileTableModel.Mode.parse(cfg.models().throughputMode()));
        RateCalculator rates = new RateCalculator(throughput, new LocalityModel(cfg.models().localityPenalty()), InterferenceModel.NONE, spec);

        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        CloudSimGpuLayer layer = new CloudSimGpuLayer();
        layer.build(shapes(spec));
        JobMetrics metrics = new JobMetrics();
        CoSchedulingBroker broker = new CoSchedulingBroker("broker", layer, spec, cfg.organisation(), workload.jobs(),
                setup, rates, metrics::record);
        ClusterMeter meter = new ClusterMeter(layer, spec, out.resolve("timeline.csv"));
        broker.setStateListener(meter::sample);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        if (broker.unfinishedJobs() > 0)
            throw new IllegalStateException(broker.unfinishedJobs() + " job(s) never finished in candidate " + cand.name());

        metrics.writeCsv(out.resolve("jobs.csv"));
        Map<String, Object> summary = metrics.summary(cfg.workload().measure());
        meter.close(((Number) summary.getOrDefault("makespan_s", 0.0)).doubleValue());
        summary.putAll(meter.utilisation());
        summary.putAll(meter.energy(cfg.energy(), metrics.rows().size(), samples(workload.jobs(), profiles, spec)));

        List<String> assumptions = assumptions(cfg);
        Map<String, Object> energyInfo = new LinkedHashMap<>();
        if (cfg.energy() != null) {
            energyInfo.put("settings", cfg.energy());
            Map<String, Object> power = new LinkedHashMap<>();
            for (ServerGroup g : spec.serverGroups()) power.put(g.name(), g.power());
            energyInfo.put("power", power);
        }
        new RunManifest()
                .put("experiment", cfg.name())
                .put("candidate", cand.name())
                .put("seed", seed)
                .put("sweep_point", point)
                .put("git_commit", RunManifest.gitCommit(yaml))
                .put("cloudsim_version", "7.0.1")
                .put("config", Map.of("file", yaml.toAbsolutePath().normalize().toString(), "sha256", RunManifest.sha256(yaml)))
                .put("workload", workloadManifest(cfg, csv, workload, workloadInfo))
                .put("models", Map.of("profiles_dir", profilesDir.toString(), "scaling", cfg.models().scaling(),
                        "throughput_mode", cfg.models().throughputMode(), "locality_penalty", cfg.models().localityPenalty(),
                        "interference", "none"))
                .put("plugins", plugins)
                .put("cluster", spec)
                .put("energy", energyInfo)
                .put("assumptions", assumptions)
                .put("warnings", throughput.warnings())
                .put("rounds", broker.rounds())
                .put("commands", broker.commandsApplied())
                .put("summary", summary)
                .put("wall_time_s", Duration.between(started, Instant.now()).toMillis() / 1000.0)
                .write(out.resolve("manifest.json"));
        return new RunResult(cand.name(), seed, out, summary, point, assumptions);
    }

    private static Map<String, Object> workloadManifest(ExperimentConfig cfg, Path csv, TraceWorkload w, Map<String, Object> info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", csv.toString());
        m.put("sha256", RunManifest.sha256(csv));
        m.put("jobs", w.jobs().size());
        m.put("generated", cfg.workload().generator() != null);
        m.putAll(info);
        if (cfg.workload().measure() != null) m.put("measure", cfg.workload().measure());
        return m;
    }

    /**
     * Training samples the run processes: iterations x the profile's batch size per job (a duration job's
     * iterations are its duration over the profile's iteration time). NaN if any profile lacks a batch size.
     */
    static double samples(List<JobRecord> jobs, ProfileRepository profiles, ClusterSpec spec) {
        String gpuType = spec.serverGroups().get(0).gpuType();
        double total = 0;
        for (JobRecord j : jobs) {
            Profile p = profiles.profileFor(j.model(), gpuType, j.gpus());
            if (p.batchSize() == null) return Double.NaN;
            double iters = j.iterations() != null ? j.iterations() : j.durationS() / p.iterTimeS();
            total += iters * p.batchSize();
        }
        return total;
    }

    /** The modelling assumptions of this configuration, in plain words, for the manifest and the report. */
    static List<String> assumptions(ExperimentConfig cfg) {
        List<String> a = new ArrayList<>();
        a.add("Jobs on separate GPUs do not slow each other (no interference), as in Synergy; Philly measured up to 12.1% "
                + "slowdown with two co-located jobs, so re-check rankings if your servers show more.");
        a.add("Trace durations (duration_s) are run time at the fixed CPU:GPU share on every GPU type.");
        a.add("Allocated CPU cores are fully busy; a GPU's busy fraction is its job's throughput divided by its throughput "
                + "at the ideal CPU allocation (speedup / ideal speedup).");
        if (cfg.models().localityPenalty() == 1.0)
            a.add("A job spread over servers runs as fast as on one server (locality penalty 1.0, as in every profile Synergy uses); "
                    + "network contention is not modelled.");
        if (cfg.workload().generator() != null)
            a.add("Workload generated per seed (" + cfg.workload().generator().name() + " " + cfg.workload().generator().params()
                    + "); every candidate runs on the same jobs for a given seed, so differences between candidates are paired.");
        if (cfg.workload().measure() != null)
            a.add("JCT statistics cover jobs " + cfg.workload().measure().fromJob() + "-" + cfg.workload().measure().toJob()
                    + " only (steady state, as Synergy measures); energy and utilisation cover the whole run.");
        if (cfg.energy() != null) {
            ExperimentConfig.Energy e = cfg.energy();
            a.add("Facility energy = IT energy x PUE " + e.pue() + " (" + e.pueSource() + ").");
            a.add("CO2 = facility energy x " + e.gridGCo2PerKwh() + " gCO2/kWh, constant over the run (" + e.gridSource()
                    + "); time-varying grid carbon and embodied carbon are out of scope.");
            for (ServerGroup g : cfg.cluster().serverGroups())
                a.add("Power of server group " + g.name() + ": CPU " + cpuText(g.power()) + ", GPU " + g.power().gpuIdleW() + "-"
                        + g.power().gpuMaxW() + " W linear, base " + g.power().baseW() + " W. Source: " + g.power().source() + ".");
            a.add("A straight line from idle to rated maximum overestimates GPU power: measured GPUs run below rated power and "
                    + "their power can saturate. Idle servers stay on (no sleep states or power capping).");
            a.add("Energy per sample takes the profile's batch_size as samples per iteration of the whole job.");
            a.add("No published paper exists to validate these energy numbers against: compare schedulers with them, "
                    + "do not read the absolute numbers as measurements.");
        }
        return a;
    }

    private static String cpuText(PowerSettings p) {
        return p.cpuModel().equals("linear") ? p.cpuIdleW() + "-" + p.cpuMaxW() + " W linear" : "SPECpower table " + p.cpuModel();
    }

    private static <T> T create(PolicyRegistry reg, Class<T> kind, String role, Candidate cand, PluginSpec spec,
                                Map<String, Object> record) {
        Params p = new Params("candidate '" + cand.name() + "' " + role + " '" + spec.name() + "'", spec.params());
        T t = reg.create(kind, spec.name(), p);
        record.put(role, Map.of("name", spec.name(), "params", p.resolved()));
        return t;
    }

    static List<ServerShape> shapes(ClusterSpec spec) {
        List<ServerShape> l = new ArrayList<>();
        for (Server s : spec.servers()) {
            ServerGroup g = s.group();
            PowerSettings p = g.power();
            PowerSpec power = p == null ? null
                    : new PowerSpec(p.cpuModel(), p.cpuIdleOrZero(), p.cpuMaxOrZero(), p.gpuIdleW(), p.gpuMaxW(), p.baseW());
            l.add(new ServerShape(s.index(), g.name(), g.gpus(), g.gpuType(), g.cpus(), g.memGb(), g.storageMbps(), g.netGbps(), power));
        }
        return l;
    }
}
