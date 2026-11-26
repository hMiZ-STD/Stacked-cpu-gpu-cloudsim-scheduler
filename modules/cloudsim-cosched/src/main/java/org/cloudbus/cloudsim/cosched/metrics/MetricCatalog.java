package org.cloudbus.cloudsim.cosched.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every metric a run reports: what it means, its unit, how to print it, and which direction is better.
 * Only metrics with a direction can be ranked or weighted as objectives; the others are shown for context.
 */
public final class MetricCatalog {

    public enum Goal { MIN, MAX, NONE }

    /**
     * @param scale   factor applied before printing (100 for fractions shown as %)
     * @param digits  decimals printed after scaling
     */
    public record Metric(String name, String label, String unit, Goal goal, double scale, int digits, String meaning) {
        public boolean ranked() {
            return goal != Goal.NONE;
        }

        /** True if a is better than b. */
        public boolean better(double a, double b) {
            return goal == Goal.MIN ? a < b : goal == Goal.MAX && a > b;
        }
    }

    private static final Map<String, Metric> ALL = new LinkedHashMap<>();

    static {
        add("mean_jct_h", "Mean JCT", "h", Goal.MIN, 1, 2, "mean job completion time, finish minus arrival (queueing included)");
        add("p99_jct_h", "p99 JCT", "h", Goal.MIN, 1, 1, "99th percentile of job completion time");
        add("p99.9_jct_h", "p99.9 JCT", "h", Goal.MIN, 1, 1, "99.9th percentile of job completion time");
        add("mean_queue_s", "Mean queueing", "h", Goal.MIN, 1.0 / 3600, 2, "mean time from arrival to first start");
        add("makespan_s", "Makespan", "h", Goal.MIN, 1.0 / 3600, 1, "time until the last job finished");
        // Not ranked: a slow scheduler keeps GPUs allocated (and fewer idle) longer, so "higher" would reward slowness.
        add("gpu_alloc_util", "GPUs allocated", "%", Goal.NONE, 100, 1, "time-averaged share of GPUs allocated to jobs (context: "
                + "slow jobs hold GPUs longer, so higher is not better)");
        add("gpu_busy_util", "GPUs busy", "%", Goal.MAX, 100, 1, "time-averaged share of GPUs computing (allocated and not waiting for data)");
        add("cpu_alloc_util", "Cores allocated", "%", Goal.NONE, 100, 1, "time-averaged share of CPU cores allocated to jobs");
        add("facility_energy_kwh", "Facility energy", "kWh", Goal.MIN, 1, 0, "IT energy times PUE, over the whole run");
        add("it_energy_kwh", "IT energy", "kWh", Goal.MIN, 1, 0, "energy of servers (CPU, GPUs, base) over the whole run");
        add("co2_kg", "CO$_2$", "kg", Goal.MIN, 1, 0, "facility energy times the grid emission factor");
        add("kwh_per_job", "Energy per job", "kWh", Goal.MIN, 1, 2, "facility energy divided by finished jobs");
        add("j_per_sample", "Energy per sample", "J", Goal.MIN, 1, 3, "facility energy divided by training samples processed");
        add("idle_gpu_energy_share", "Idle-GPU energy", "%", Goal.NONE, 100, 1, "share of IT energy drawn by GPUs no job holds "
                + "(context: it falls when slow jobs occupy GPUs longer, so lower is not better)");
        add("stalled_gpu_energy_share", "Stalled-GPU energy", "%", Goal.MIN, 100, 1,
                "share of IT energy drawn by allocated GPUs waiting for data (CPU- or memory-starved)");
        add("mean_it_power_kw", "Mean IT power", "kW", Goal.NONE, 1, 1, "IT energy divided by the makespan");
        add("cpu_energy_share", "CPU energy", "%", Goal.NONE, 100, 1, "share of IT energy drawn by CPUs");
        add("gpu_energy_share", "GPU energy", "%", Goal.NONE, 100, 1, "share of IT energy drawn by GPUs");
    }

    private static void add(String name, String label, String unit, Goal goal, double scale, int digits, String meaning) {
        ALL.put(name, new Metric(name, label, unit, goal, scale, digits, meaning));
    }

    private MetricCatalog() {
    }

    public static Metric get(String name) {
        Metric m = ALL.get(name);
        if (m == null) throw new IllegalArgumentException("unknown metric '" + name + "'; known: " + ALL.keySet());
        return m;
    }

    public static boolean has(String name) {
        return ALL.containsKey(name);
    }

    /** All metrics, in report order. */
    public static List<Metric> all() {
        return List.copyOf(ALL.values());
    }
}
