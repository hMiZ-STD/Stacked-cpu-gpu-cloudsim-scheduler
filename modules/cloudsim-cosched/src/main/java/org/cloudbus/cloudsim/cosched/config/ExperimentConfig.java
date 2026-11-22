package org.cloudbus.cloudsim.cosched.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
        @JsonProperty("output_dir") String outputDir) {

    /** A plug-in choice: its registered name and its parameters. */
    public record PluginSpec(String name, Map<String, Object> params) {
        public PluginSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("plug-in needs a name");
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** Where jobs come from and how their models are assigned. */
    public record Workload(String csv, PluginSpec labeller) {
        public Workload {
            if (csv == null || csv.isBlank()) throw new IllegalArgumentException("workload.csv is required");
            if (labeller == null) labeller = new PluginSpec("from_trace", null);
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
    }

    /** Reads and validates an experiment YAML. Errors name the field and the file. */
    public static ExperimentConfig load(Path yaml) {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        try {
            return m.readValue(yaml.toFile(), ExperimentConfig.class);
        } catch (JsonProcessingException e) {
            Throwable c = e;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            String why = c instanceof IllegalArgumentException ? c.getMessage() : e.getOriginalMessage();
            throw new IllegalArgumentException("invalid experiment " + yaml + ": " + why, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read experiment " + yaml + ": " + e.getMessage(), e);
        }
    }

    /** Resolves a path written in the YAML against the YAML's folder. */
    public static Path resolve(Path yaml, String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : yaml.toAbsolutePath().getParent().resolve(p).normalize();
    }
}
