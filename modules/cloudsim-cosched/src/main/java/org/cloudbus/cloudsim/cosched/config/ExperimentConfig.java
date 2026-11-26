package org.cloudbus.cloudsim.cosched.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.Organisation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One experiment, as written in its YAML file (format in {@code docs/formats.md}). Unknown keys are
 * errors, so typos are caught. Relative paths are resolved against the YAML file's folder.
 */
public record ExperimentConfig(
        String name,
        ClusterSpec cluster,
        Organisation organisation,
        Workload workload,
        Models models,
        List<Candidate> candidates,
        List<Long> seeds,
        @JsonProperty("output_dir") String outputDir,
        Energy energy,
        List<Sweep> sweeps,
        Report report) {

    /** A plug-in choice: its registered name and its parameters. */
    public record PluginSpec(String name, Map<String, Object> params) {
        public PluginSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("plug-in needs a name");
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * Where jobs come from (a fixed job CSV, or a generator driven by the run's seed), how their models are
     * assigned, and optionally which jobs the JCT statistics cover.
     */
    public record Workload(String csv, PluginSpec generator, PluginSpec labeller, Measure measure) {
        public Workload {
            boolean hasCsv = csv != null && !csv.isBlank();
            if (hasCsv == (generator != null))
                throw new IllegalArgumentException("workload needs exactly one of csv or generator");
            if (labeller == null) labeller = new PluginSpec("from_trace", null);
        }

        public Workload(String csv, PluginSpec labeller) {
            this(csv, null, labeller, null);
        }
    }

    /**
     * Jobs whose JCTs the summary covers, by id, both ends included (Synergy measures jobs 4000-4999 of a
     * longer run so the cluster is in steady state). Energy and utilisation always cover the whole run.
     */
    public record Measure(@JsonProperty("from_job") int fromJob, @JsonProperty("to_job") int toJob) {
        public Measure {
            if (fromJob < 0 || toJob < fromJob) throw new IllegalArgumentException("workload.measure needs 0 <= from_job <= to_job");
        }

        public boolean covers(int jobId) {
            return fromJob <= jobId && jobId <= toJob;
        }
    }

    /**
     * Settings that turn server energy into facility energy and CO2. All four are required when the
     * section is present: there are no defaults, and each number needs its source.
     */
    public record Energy(Double pue, @JsonProperty("pue_source") String pueSource,
                         @JsonProperty("grid_g_co2_per_kwh") Double gridGCo2PerKwh,
                         @JsonProperty("grid_source") String gridSource) {
        public Energy {
            if (pue == null || !(pue >= 1 && pue <= 5)) throw new IllegalArgumentException("energy.pue is required, in [1, 5]");
            if (gridGCo2PerKwh == null || !(gridGCo2PerKwh >= 0 && gridGCo2PerKwh <= 2000))
                throw new IllegalArgumentException("energy.grid_g_co2_per_kwh is required, in [0, 2000]");
            if (pueSource == null || pueSource.isBlank()) throw new IllegalArgumentException("energy.pue_source is required");
            if (gridSource == null || gridSource.isBlank()) throw new IllegalArgumentException("energy.grid_source is required");
        }
    }

    /**
     * One what-if axis: the value at {@code path} in this YAML (dots between keys, list positions as
     * numbers, {@code *} for every list element) takes each of {@code values} in turn. Several sweeps
     * multiply. Example: {@code { name: load, path: workload.generator.params.jobs_per_hour, values: [6, 9] }}.
     *
     * <p>When two settings must change together, give {@code paths} instead and make each value a list with
     * one entry per path; the first entry names the point. Example: more cores per server also needs the
     * profiles normalised to that server's fixed share:
     * {@code { name: cpus, paths: [cluster.server_groups.0.cpus, models.profiles_dir],
     *          values: [[24, ../profiles/synergy-v100/models], [32, ../profiles/synergy-v100/models-4]] }}.
     */
    public record Sweep(String name, String path, List<String> paths, List<Object> values) {
        public Sweep {
            if ((path == null || path.isBlank()) == (paths == null || paths.isEmpty()))
                throw new IllegalArgumentException("every sweep needs exactly one of path or paths");
            paths = path != null && !path.isBlank() ? List.of(path) : List.copyOf(paths);
            for (String p : paths)
                if (p.startsWith("sweeps") || p.startsWith("report") || p.startsWith("output_dir") || p.startsWith("seeds"))
                    throw new IllegalArgumentException("sweep path " + p + ": cannot sweep sweeps, report, seeds or output_dir");
            if (values == null || values.isEmpty()) throw new IllegalArgumentException("sweep " + paths + " needs at least one value");
            if (paths.size() > 1)
                for (Object v : values)
                    if (!(v instanceof List<?> l) || l.size() != paths.size())
                        throw new IllegalArgumentException("sweep " + paths + ": each value must be a list of " + paths.size()
                                + " entries, one per path; got " + v);
            if (name == null || name.isBlank()) name = paths.get(0).substring(paths.get(0).lastIndexOf('.') + 1);
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("sweep name '" + name + "': use letters, digits, _ . -");
            values = List.copyOf(values);
            path = null;
        }

        /** The values to set, one per path, for one entry of {@code values}. */
        public List<Object> settings(Object value) {
            return paths.size() == 1 ? java.util.Collections.singletonList(value) : List.copyOf((List<?>) value);
        }

        /** What names the point: the value, or the first entry of a multi-path value. */
        public Object shownValue(Object value) {
            return settings(value).get(0);
        }
    }

    /** One metric the decision report weighs. Metric names are checked against the report's catalogue. */
    public record Objective(String metric, Double weight) {
        public Objective {
            if (metric == null || metric.isBlank()) throw new IllegalArgumentException("every objective needs a metric");
            if (weight == null || !(weight > 0)) throw new IllegalArgumentException("objective " + metric + ": weight is required and must be > 0");
        }
    }

    /**
     * What the decision report shows: {@code metrics} (all available when absent) and the researcher's
     * {@code objectives} with weights (no weighted ranking when absent: the report then ranks per metric only).
     */
    public record Report(List<String> metrics, List<Objective> objectives) {
        public Report {
            metrics = metrics == null ? null : List.copyOf(metrics);
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
            if (objectives.stream().map(Objective::metric).distinct().count() != objectives.size())
                throw new IllegalArgumentException("report.objectives: each metric at most once");
        }
    }

    /** Performance-model settings shared by every candidate in the experiment. */
    public record Models(
            @JsonProperty("profiles_dir") String profilesDir,
            String scaling,
            @JsonProperty("throughput_mode") String throughputMode,
            @JsonProperty("locality_penalty") Double localityPenalty) {
        public Models {
            if (profilesDir == null || profilesDir.isBlank()) throw new IllegalArgumentException("models.profiles_dir is required");
            if (scaling == null) scaling = "linear";
            if (throughputMode == null) throughputMode = "table";
            if (localityPenalty == null) localityPenalty = 1.0;
        }
    }

    /** One scheduler to compare: ordering + allocation + placement + lifecycle + trigger. */
    public record Candidate(String name, PluginSpec trigger, PluginSpec ordering, PluginSpec allocation,
                            PluginSpec placement, PluginSpec lifecycle) {
        public Candidate {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("every candidate needs a name");
            need(name, "trigger", trigger);
            need(name, "ordering", ordering);
            need(name, "allocation", allocation);
            need(name, "placement", placement);
            need(name, "lifecycle", lifecycle);
        }

        private static void need(String candidate, String what, PluginSpec spec) {
            if (spec == null) throw new IllegalArgumentException("candidate '" + candidate + "' needs a " + what);
        }
    }

    public ExperimentConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("experiment needs a name");
        if (cluster == null) throw new IllegalArgumentException("cluster is required");
        if (organisation == null) organisation = Organisation.single();
        if (workload == null) throw new IllegalArgumentException("workload is required");
        if (models == null) throw new IllegalArgumentException("models is required");
        if (candidates == null || candidates.isEmpty()) throw new IllegalArgumentException("at least one candidate is required");
        if (candidates.stream().map(Candidate::name).distinct().count() != candidates.size())
            throw new IllegalArgumentException("candidate names must be unique");
        if (seeds == null || seeds.isEmpty()) seeds = List.of(42L);
        if (outputDir == null || outputDir.isBlank()) outputDir = "out/" + name;
        candidates = List.copyOf(candidates);
        seeds = List.copyOf(seeds);
        if (seeds.stream().distinct().count() != seeds.size()) throw new IllegalArgumentException("seeds must be distinct");
        boolean anyPower = cluster.serverGroups().stream().anyMatch(g -> g.power() != null);
        if (energy != null) {
            for (var g : cluster.serverGroups())
                if (g.power() == null)
                    throw new IllegalArgumentException("energy is modelled, so server group '" + g.name() + "' needs a power section "
                            + "(no defaults: every number needs a source)");
        } else if (anyPower) {
            throw new IllegalArgumentException("server groups have power settings but there is no energy section "
                    + "(pue, pue_source, grid_g_co2_per_kwh, grid_source); add it, or remove the power settings");
        }
        sweeps = sweeps == null ? List.of() : List.copyOf(sweeps);
        if (sweeps.stream().map(Sweep::name).distinct().count() != sweeps.size())
            throw new IllegalArgumentException("sweep names must be unique");
        if (report == null) report = new Report(null, null);
    }

    /** Same experiment without sweeps etc. (used by tests and simple presets). */
    public ExperimentConfig(String name, ClusterSpec cluster, Organisation organisation, Workload workload, Models models,
                            List<Candidate> candidates, List<Long> seeds, String outputDir) {
        this(name, cluster, organisation, workload, models, candidates, seeds, outputDir, null, null, null);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /** Reads and validates an experiment YAML. Errors name the field and the file. */
    public static ExperimentConfig load(Path yaml) {
        return bind(readTree(yaml), yaml.toString());
    }

    /** The YAML as a tree, before validation (sweeps edit this tree). */
    public static ObjectNode readTree(Path yaml) {
        try {
            JsonNode n = mapper().readTree(yaml.toFile());
            if (!(n instanceof ObjectNode o)) throw new IllegalArgumentException("invalid experiment " + yaml + ": not a YAML mapping");
            return o;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid experiment " + yaml + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read experiment " + yaml + ": " + e.getMessage(), e);
        }
    }

    /** Validates a YAML tree. {@code where} names it in error messages. */
    public static ExperimentConfig bind(JsonNode tree, String where) {
        try {
            return mapper().treeToValue(tree, ExperimentConfig.class);
        } catch (JsonProcessingException e) {
            Throwable c = e;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            String why = c instanceof IllegalArgumentException ? c.getMessage() : e.getOriginalMessage();
            throw new IllegalArgumentException("invalid experiment " + where + ": " + why, e);
        }
    }

    /** Resolves a path written in the YAML against the YAML's folder. */
    public static Path resolve(Path yaml, String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : yaml.toAbsolutePath().getParent().resolve(p).normalize();
    }
}
