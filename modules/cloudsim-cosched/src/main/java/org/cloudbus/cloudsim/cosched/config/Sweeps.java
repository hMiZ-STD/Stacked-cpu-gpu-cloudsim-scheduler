package org.cloudbus.cloudsim.cosched.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands an experiment's sweeps into one configuration per combination of values (the cartesian product,
 * first sweep varying slowest). Each sweep sets a value inside the YAML tree, so anything written in the YAML
 * can be swept: a cluster dimension, a policy parameter, the workload's load. The edited tree is then
 * validated like a hand-written file, so a wrong path or value fails before any run starts.
 */
public final class Sweeps {

    /** One combination: its label (used as a folder name), its values by sweep name, and the edited YAML tree. */
    public record Point(String label, Map<String, Object> values, ObjectNode tree) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private Sweeps() {
    }

    public static List<Point> expand(ObjectNode tree, List<ExperimentConfig.Sweep> sweeps) {
        List<Point> points = new ArrayList<>();
        if (sweeps.isEmpty()) {
            points.add(new Point("", Map.of(), tree.deepCopy()));
            return points;
        }
        expand(tree, sweeps, 0, new LinkedHashMap<>(), points);
        return points;
    }

    private static void expand(ObjectNode tree, List<ExperimentConfig.Sweep> sweeps, int i, LinkedHashMap<String, Object> chosen,
                               List<Point> out) {
        if (i == sweeps.size()) {
            ObjectNode copy = tree.deepCopy();
            LinkedHashMap<String, Object> shown = new LinkedHashMap<>();
            for (ExperimentConfig.Sweep s : sweeps) {
                Object v = chosen.get(s.name());
                List<Object> settings = s.settings(v);
                for (int k = 0; k < s.paths().size(); k++) set(copy, s.paths().get(k), JSON.valueToTree(settings.get(k)));
                shown.put(s.name(), s.shownValue(v));
            }
            Map<String, Object> values = java.util.Collections.unmodifiableMap(shown);
            out.add(new Point(labelOf(values), values, copy));
            return;
        }
        ExperimentConfig.Sweep s = sweeps.get(i);
        for (Object v : s.values()) {
            chosen.put(s.name(), v);
            expand(tree, sweeps, i + 1, chosen, out);
        }
        chosen.remove(s.name());
    }

    /** Folder-safe label of a point, e.g. {@code cpus=32_jobs_per_hour=9}; empty without sweeps. */
    public static String labelOf(Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) parts.add(e.getKey() + "=" + e.getValue());
        return String.join("_", parts).replaceAll("[^A-Za-z0-9_.=-]", "-");
    }

    /**
     * Sets {@code value} at {@code path}: keys separated by dots, list positions as numbers, {@code *} for every
     * element of a list. Missing keys are created (a misspelt key then fails validation as an unknown key);
     * a missing list position is an error.
     */
    public static void set(ObjectNode root, String path, JsonNode value) {
        String[] seg = path.split("\\.");
        int n = setAt(root, seg, 0, value, path);
        if (n == 0) throw new IllegalArgumentException("sweep path " + path + " matches nothing");
    }

    private static int setAt(JsonNode node, String[] seg, int i, JsonNode value, String path) {
        String key = seg[i];
        boolean last = i == seg.length - 1;
        if (node instanceof ObjectNode o) {
            if (key.equals("*")) throw new IllegalArgumentException("sweep path " + path + ": '*' only matches list elements");
            if (last) {
                o.set(key, value);
                return 1;
            }
            JsonNode child = o.get(key);
            if (child == null || child.isNull()) {
                child = o.putObject(key);
            }
            return setAt(child, seg, i + 1, value, path);
        }
        if (node instanceof ArrayNode a) {
            List<Integer> idx = new ArrayList<>();
            if (key.equals("*")) {
                for (int k = 0; k < a.size(); k++) idx.add(k);
            } else {
                int k;
                try {
                    k = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("sweep path " + path + ": '" + key + "' must be a list position or '*'");
                }
                if (k < 0 || k >= a.size())
                    throw new IllegalArgumentException("sweep path " + path + ": list position " + k + " does not exist (size " + a.size() + ")");
                idx.add(k);
            }
            int n = 0;
            for (int k : idx) {
                if (last) {
                    a.set(k, value);
                    n++;
                } else n += setAt(a.get(k), seg, i + 1, value, path);
            }
            return n;
        }
        throw new IllegalArgumentException("sweep path " + path + ": '" + seg[i - 1] + "' is a value, not a section");
    }
}
