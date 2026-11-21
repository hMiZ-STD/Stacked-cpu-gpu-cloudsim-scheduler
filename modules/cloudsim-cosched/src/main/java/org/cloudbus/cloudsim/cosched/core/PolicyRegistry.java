package org.cloudbus.cloudsim.cosched.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Maps the names used in the experiment YAML to plug-in factories, per plug-in kind
 * (ordering, allocation, ...). The core never names a concrete policy (architecture rule 7);
 * concrete policies are registered from outside, e.g. by a standard-library bootstrap class.
 */
public final class PolicyRegistry {

    private final Map<Class<?>, Map<String, Function<Params, ?>>> factories = new HashMap<>();

    public <T> PolicyRegistry register(Class<T> kind, String name, Function<Params, ? extends T> factory) {
        Map<String, Function<Params, ?>> byName = factories.computeIfAbsent(kind, k -> new HashMap<>());
        if (byName.putIfAbsent(name, factory) != null)
            throw new IllegalArgumentException(kind.getSimpleName() + " '" + name + "' is already registered");
        return this;
    }

    /**
     * Builds the plug-in. Unknown names fail with the list of known ones; unknown or
     * out-of-range parameters fail through {@link Params}.
     */
    public <T> T create(Class<T> kind, String name, Params params) {
        Function<Params, ?> f = factories.getOrDefault(kind, Map.of()).get(name);
        if (f == null)
            throw new IllegalArgumentException("unknown " + kind.getSimpleName() + " '" + name + "'; known: " + names(kind));
        T instance = kind.cast(f.apply(params));
        params.checkNoUnknown();
        return instance;
    }

    public boolean has(Class<?> kind, String name) {
        return factories.getOrDefault(kind, Map.of()).containsKey(name);
    }

    public Set<String> names(Class<?> kind) {
        return new TreeSet<>(factories.getOrDefault(kind, Map.of()).keySet());
    }
}
