package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.ClusterState;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.ProfileRepository;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A hand-built cluster, jobs and plan for testing policies without a simulation. */
public final class PolicyFixtures {

    private PolicyFixtures() {
    }

    public static final ServerGroup V100 = new ServerGroup("v100", 1, 8, "V100", 24, 500, 500, 40);

    public static ClusterSpec servers(int n) {
        return new ClusterSpec(List.of(new ServerGroup("v100", n, 8, "V100", 24, 500, 500, 40)));
    }

    public static ProfileTableModel twoPoint() {
        return new ProfileTableModel(ProfileRepository.load(Path.of("../../presets/profiles/synergy-v100/models"),
                ProfileRepository.Scaling.LINEAR), ProfileTableModel.Mode.SYNERGY_TWO_POINT);
    }

    /** A job with settable fields. */
    public static final class J implements JobInfo {
        final int id, gpus;
        final String model;
        double arrival, remainingAtShare = 1000, totalAtShare = 1000, attained, lastRun = Double.NaN;
        Allocation alloc;

        public J(int id, String model, int gpus) {
            this.id = id;
            this.model = model;
            this.gpus = gpus;
            this.arrival = id;
        }

        public J arrival(double t) { arrival = t; return this; }
        public J remaining(double s) { remainingAtShare = s; return this; }
        public J total(double s) { totalAtShare = s; return this; }
        public J attained(double s) { attained = s; return this; }
        public J lastRun(double t) { lastRun = t; return this; }
        public J running(Allocation a) { alloc = a; return this; }

        public int id() { return id; }
        public double arrivalTime() { return arrival; }
        public int gpus() { return gpus; }
        public String model() { return model; }
        public String team() { return Organisation.DEFAULT; }
        public String priority() { return Organisation.DEFAULT; }
        public double totalWork() { return 1000; }
        public double remainingWork() { return 1000; }
        public double remainingTimeAtProportionalShare() { return remainingAtShare; }
        public double totalTimeAtProportionalShare() { return totalAtShare; }
        public double attainedServiceGpuSeconds() { return attained; }
        public double firstStartTime() { return Double.NaN; }
        public Allocation allocation() { return alloc; }
        public double lastRunTime() { return lastRun; }
    }

    public record State(double now, ClusterSpec spec, List<JobInfo> activeJobs) implements ClusterState {
        public List<Server> servers() { return spec.servers(); }
        public Organisation organisation() { return Organisation.single(); }
        public ResourceVector free(int s) {
            ResourceVector used = ResourceVector.ZERO;
            for (JobInfo j : activeJobs) if (j.isRunning()) used = used.plus(j.allocation().on(s));
            return spec.servers().get(s).capacity().minus(used).toVector();
        }
    }

    public static RoundPlan plan(ClusterSpec spec, RoundPlan.Start start, JobInfo... jobs) {
        return new RoundPlan(new State(0, spec, new ArrayList<>(List.of(jobs))), start);
    }

    public static ResourceVector rv(int g, double c, double m) {
        return new ResourceVector(g, c, m);
    }
}
