package org.cloudbus.cloudsim.cosched.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * All profiles of one run, looked up by (model, GPU type, GPU count). A missing profile stops the
 * run with a clear error; there is never a silent fallback to another GPU type or count.
 */
public final class ProfileRepository {

    /** How a job on several GPUs finds its profile. */
    public enum Scaling {
        /** Use the 1-GPU profile for any GPU count (what Synergy's simulator does). */
        LINEAR,
        /** Use the profile measured for that exact GPU count. */
        PER_GPU_COUNT;

        public static Scaling parse(String s) {
            return switch (s) {
                case "linear" -> LINEAR;
                case "per_gpu_count" -> PER_GPU_COUNT;
                default -> throw new IllegalArgumentException("scaling must be 'linear' or 'per_gpu_count', got '" + s + "'");
            };
        }
    }

    private final Map<Profile.Key, Profile> byKey;
    private final Scaling scaling;

    public ProfileRepository(Collection<Profile> profiles, Scaling scaling) {
        Map<Profile.Key, Profile> m = new LinkedHashMap<>();
        for (Profile p : profiles) {
            Profile prev = m.put(p.key(), p);
            if (prev != null) throw new IllegalArgumentException("two profiles for " + p.key() + ": " + prev.source() + " and " + p.source());
        }
        this.byKey = Collections.unmodifiableMap(m);
        this.scaling = scaling;
    }

    /** Reads every {@code *.json} file directly inside {@code dir}. */
    public static ProfileRepository load(Path dir, Scaling scaling) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("profile directory not found: " + dir);
        ObjectMapper json = new ObjectMapper();
        try (Stream<Path> files = Files.list(dir)) {
            List<Profile> profiles = files.filter(f -> f.toString().endsWith(".json")).sorted().map(f -> {
                try {
                    return json.readValue(f.toFile(), Profile.class);
                } catch (IOException e) {
                    throw new IllegalArgumentException("cannot read profile " + f + ": " + e.getMessage(), e);
                }
            }).toList();
            if (profiles.isEmpty()) throw new IllegalArgumentException("no profile JSON files in " + dir);
            return new ProfileRepository(profiles, scaling);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Scaling scaling() {
        return scaling;
    }

    /** The profile a job of {@code model} on {@code gpus} GPUs of {@code gpuType} uses. */
    public Profile profileFor(String model, String gpuType, int gpus) {
        int count = scaling == Scaling.LINEAR ? 1 : gpus;
        Profile p = byKey.get(new Profile.Key(model, gpuType, count));
        if (p == null)
            throw new IllegalArgumentException("no profile for model '" + model + "' on " + count + " x " + gpuType
                    + " (scaling " + scaling.name().toLowerCase() + "); available: " + byKey.keySet());
        return p;
    }

    public boolean has(String model, String gpuType, int gpus) {
        return byKey.containsKey(new Profile.Key(model, gpuType, scaling == Scaling.LINEAR ? 1 : gpus));
    }

    /**
     * Checks before the run that every (model, GPU type) pair the run can need has a profile, so a
     * missing one is reported up front instead of hours into a run.
     */
    public void requireAll(Set<String> models, Set<String> gpuTypes, Set<Integer> gpuCounts) {
        Set<String> missing = new TreeSet<>();
        for (String m : models)
            for (String t : gpuTypes)
                for (int g : gpuCounts)
                    if (!has(m, t, g)) missing.add(m + " on " + (scaling == Scaling.LINEAR ? 1 : g) + " x " + t);
        if (!missing.isEmpty()) throw new IllegalArgumentException("missing profiles: " + missing);
    }

    public Collection<Profile> all() {
        return byKey.values();
    }
}
