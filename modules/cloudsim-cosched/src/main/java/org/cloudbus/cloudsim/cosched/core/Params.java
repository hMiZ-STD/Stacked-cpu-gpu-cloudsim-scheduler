package org.cloudbus.cloudsim.cosched.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parameters of one plug-in, as written in the experiment YAML. Every read states a default
 * and a valid range, so values are range-checked at startup; every resolved value (given or
 * defaulted) is kept for the {@code RunManifest}. {@link #checkNoUnknown()} catches typos.
 */
public final class Params {

    private final String owner;
    private final Map<String, Object> raw;
    private final LinkedHashMap<String, Object> resolved = new LinkedHashMap<>();

    public Params(String owner, Map<String, ?> raw) {
        this.owner = owner;
        this.raw = raw == null ? Map.of() : new HashMap<>(raw);
    }

    public static Params none(String owner) {
        return new Params(owner, Map.of());
    }

    public double getDouble(String key, double def, double min, double max) {
        Object v = raw.get(key);
        double d;
        if (v == null) d = def;
        else if (v instanceof Number n) d = n.doubleValue();
        else throw bad(key, "must be a number, got '" + v + "'");
        if (d < min || d > max) throw bad(key, "= " + d + " is outside [" + min + ", " + max + "]");
        resolved.put(key, d);
        return d;
    }

    /** A number the YAML must give: there is no sensible default (e.g. a workload's load). */
    public double requireDouble(String key, double min, double max) {
        if (raw.get(key) == null) throw bad(key, "is required");
        return getDouble(key, 0, min, max);
    }

    /** An integer the YAML must give. */
    public int requireInt(String key, int min, int max) {
        if (raw.get(key) == null) throw bad(key, "is required");
        return getInt(key, 0, min, max);
    }

    /** A free-text value (e.g. a file path), or {@code def} (may be null) when absent. */
    public String getString(String key, String def) {
        Object v = raw.get(key);
        if (v != null && !(v instanceof String)) throw bad(key, "must be text, got '" + v + "'");
        String s = v == null ? def : (String) v;
        if (s != null) resolved.put(key, s);
        return s;
    }

    /** True if the YAML set this key. */
    public boolean has(String key) {
        return raw.containsKey(key);
    }

    public int getInt(String key, int def, int min, int max) {
        Object v = raw.get(key);
        int i;
        if (v == null) i = def;
        else if (v instanceof Integer || v instanceof Long || v instanceof Short) i = ((Number) v).intValue();
        else throw bad(key, "must be an integer, got '" + v + "'");
        if (i < min || i > max) throw bad(key, "= " + i + " is outside [" + min + ", " + max + "]");
        resolved.put(key, i);
        return i;
    }

    public boolean getBoolean(String key, boolean def) {
        Object v = raw.get(key);
        boolean b;
        if (v == null) b = def;
        else if (v instanceof Boolean bo) b = bo;
        else throw bad(key, "must be true or false, got '" + v + "'");
        resolved.put(key, b);
        return b;
    }

    public String getChoice(String key, String def, List<String> allowed) {
        Object v = raw.get(key);
        String s = v == null ? def : v.toString();
        if (!allowed.contains(s)) throw bad(key, "= '" + s + "' must be one of " + allowed);
        resolved.put(key, s);
        return s;
    }

    /** A list of {@code size} numbers, each in [min, max]. */
    public List<Double> getDoubleList(String key, List<Double> def, int size, double min, double max) {
        Object v = raw.get(key);
        List<Double> out = new java.util.ArrayList<>();
        if (v == null) out.addAll(def);
        else if (v instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Number n)) throw bad(key, "must be a list of numbers, got '" + v + "'");
                out.add(n.doubleValue());
            }
        } else throw bad(key, "must be a list, got '" + v + "'");
        if (out.size() != size) throw bad(key, "must have " + size + " values, got " + out.size());
        for (double d : out) if (d < min || d > max) throw bad(key, "value " + d + " is outside [" + min + ", " + max + "]");
        resolved.put(key, List.copyOf(out));
        return List.copyOf(out);
    }

    /** Throws if the YAML set a key no plug-in read (usually a typo). Call after construction. */
    public void checkNoUnknown() {
        Set<String> unknown = new TreeSet<>(raw.keySet());
        unknown.removeAll(resolved.keySet());
        if (!unknown.isEmpty()) throw new IllegalArgumentException(owner + ": unknown parameter(s) " + unknown);
    }

    /** Every value the plug-in used, defaults included, for the run manifest. */
    public Map<String, Object> resolved() {
        return Collections.unmodifiableMap(resolved);
    }

    private IllegalArgumentException bad(String key, String msg) {
        return new IllegalArgumentException(owner + ": parameter '" + key + "' " + msg);
    }
}
