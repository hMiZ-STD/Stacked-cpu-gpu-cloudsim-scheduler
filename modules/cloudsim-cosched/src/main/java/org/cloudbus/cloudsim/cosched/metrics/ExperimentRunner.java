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
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.InterferenceModel;
import org.cloudbus.cloudsim.cosched.model.LocalityModel;
import org.cloudbus.cloudsim.cosched.model.ProfileRepository;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;
import org.cloudbus.cloudsim.cosched.model.RateCalculator;
import org.cloudbus.cloudsim.cosched.policy.AllocationPolicy;
import org.cloudbus.cloudsim.cosched.policy.JointAllocationPlacement;
import org.cloudbus.cloudsim.cosched.policy.LifecycleController;
import org.cloudbus.cloudsim.cosched.policy.OrderingPolicy;
import org.cloudbus.cloudsim.cosched.policy.PlacementPolicy;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;
import org.cloudbus.cloudsim.cosched.workload.JobRecord;
import org.cloudbus.cloudsim.cosched.workload.ModelLabeler;
import org.cloudbus.cloudsim.cosched.workload.TraceWorkload;
import org.cloudbus.cloudsim.gpu.CloudSimGpuLayer;
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
 * Runs an experiment YAML: every candidate x seed, each in its own fresh simulation. Output per run:
 * {@code <output_dir>/<candidate>/seed-<seed>/jobs.csv} and {@code manifest.json}.
 *
 * <pre>java ... org.cloudbus.cloudsim.cosched.metrics.ExperimentRunner presets/smoke/smoke.yaml</pre>
 */
public final class ExperimentRunner {

    /** Result of one run. */
    public record RunResult(String candidate, long seed, Path outputDir, Map<String, Object> summary) {
    }

    private ExperimentRunner() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ExperimentRunner <experiment.yaml>");
            System.exit(1);
        }
        Path yaml = Path.of(args[0]);
        for (RunResult r : run(yaml))
            System.out.println(r.candidate() + " seed " + r.seed() + ": " + r.summary() + " -> " + r.outputDir());
    }

    public static List<RunResult> run(Path yaml) {
        ExperimentConfig cfg = ExperimentConfig.load(yaml);
        List<RunResult> results = new ArrayList<>();
        for (Candidate c : cfg.candidates())
            for (long seed : cfg.seeds()) results.add(runOne(yaml, cfg, c, seed));
        return results;
    }

    static RunResult runOne(Path yaml, ExperimentConfig cfg, Candidate cand, long seed) {
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
        Map<String, Object> labellerInfo = new LinkedHashMap<>();
        ModelLabeler labeller = create(reg, ModelLabeler.class, "labeller", cand, cfg.workload().labeller(), labellerInfo);

        Path csv = ExperimentConfig.resolve(yaml, cfg.workload().csv());
        TraceWorkload workload = TraceWorkload.fromCsv(csv, labeller);
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
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        if (broker.unfinishedJobs() > 0)
            throw new IllegalStateException(broker.unfinishedJobs() + " job(s) never finished in candidate " + cand.name());

        Path out = ExperimentConfig.resolve(yaml, cfg.outputDir()).resolve(cand.name()).resolve("seed-" + seed);
        metrics.writeCsv(out.resolve("jobs.csv"));
        Map<String, Object> summary = metrics.summary();
        new RunManifest()
                .put("experiment", cfg.name())
                .put("candidate", cand.name())
                .put("seed", seed)
                .put("git_commit", RunManifest.gitCommit(yaml))
                .put("cloudsim_version", "7.0.1")
                .put("config", Map.of("file", yaml.toAbsolutePath().normalize().toString(), "sha256", RunManifest.sha256(yaml)))
                .put("workload", Map.of("file", csv.toString(), "sha256", RunManifest.sha256(csv), "jobs", workload.jobs().size(),
                        "labeller", labellerInfo.get("labeller")))
                .put("models", Map.of("profiles_dir", profilesDir.toString(), "scaling", cfg.models().scaling(),
                        "throughput_mode", cfg.models().throughputMode(), "locality_penalty", cfg.models().localityPenalty(),
                        "interference", "none"))
                .put("plugins", plugins)
                .put("cluster", spec)
                .put("assumptions", List.of(
                        "Jobs on separate GPUs do not slow each other (no interference), as in Synergy; Philly measured up to 12.1% slowdown with two co-located jobs.",
                        "Trace durations (duration_s) are run time at the fixed CPU:GPU share on every GPU type."))
                .put("warnings", throughput.warnings())
                .put("rounds", broker.rounds())
                .put("commands", broker.commandsApplied())
                .put("summary", summary)
                .put("wall_time_s", Duration.between(started, Instant.now()).toMillis() / 1000.0)
                .write(out.resolve("manifest.json"));
        return new RunResult(cand.name(), seed, out, summary);
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
            l.add(new ServerShape(s.index(), g.name(), g.gpus(), g.gpuType(), g.cpus(), g.memGb(), g.storageMbps(), g.netGbps()));
        }
        return l;
    }
}
