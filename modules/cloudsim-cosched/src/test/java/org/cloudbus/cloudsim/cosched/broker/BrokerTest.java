package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.ResourceVector;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.InterferenceModel;
import org.cloudbus.cloudsim.cosched.model.LocalityModel;
import org.cloudbus.cloudsim.cosched.model.ProfileRepository;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;
import org.cloudbus.cloudsim.cosched.model.RateCalculator;
import org.cloudbus.cloudsim.cosched.policy.SchedulingTrigger;
import org.cloudbus.cloudsim.cosched.policy.impl.ConsolidatedPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FifoOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.GpuProportionalAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.NonPreemptiveLifecycle;
import org.cloudbus.cloudsim.cosched.policy.impl.Triggers;
import org.cloudbus.cloudsim.cosched.workload.JobRecord;
import org.cloudbus.cloudsim.gpu.CloudSimGpuLayer;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrokerTest {

    static final double TOL = 0.05;
    static final ClusterSpec ONE_SERVER = new ClusterSpec(List.of(new ServerGroup("v100", 1, 8, "V100", 24, 500, 500, 40)));

    /** transformer: speedup 1 at the fixed share; iter_time 1 s, so rate = 1 iteration/s. */
    static JobRecord job(int id, double arrival, int gpus, double iterations) {
        return new JobRecord(id, arrival, gpus, iterations, 1.0, null, "transformer", null, null, id + 2);
    }

    static List<Job> run(ClusterSpec spec, List<JobRecord> jobs, SchedulingTrigger trigger) {
        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        CloudSimGpuLayer layer = new CloudSimGpuLayer();
        List<ServerShape> shapes = new ArrayList<>();
        for (var s : spec.servers())
            shapes.add(new ServerShape(s.index(), s.group().name(), s.group().gpus(), s.group().gpuType(), s.group().cpus(), s.group().memGb(), 0, 0));
        layer.build(shapes);
        ProfileRepository repo = ProfileRepository.load(Path.of("../../presets/profiles/synergy-v100/models"), ProfileRepository.Scaling.LINEAR);
        RateCalculator rates = new RateCalculator(new ProfileTableModel(repo, ProfileTableModel.Mode.TABLE),
                new LocalityModel(1.0), InterferenceModel.NONE, spec);
        SchedulerSetup setup = new SchedulerSetup(trigger, new FifoOrdering(), new GpuProportionalAllocation(),
                new ConsolidatedPlacement(), new NonPreemptiveLifecycle());
        List<Job> ended = new ArrayList<>();
        CoSchedulingBroker b = new CoSchedulingBroker("broker", layer, spec, Organisation.single(), jobs, setup, rates, ended::add);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        assertEquals(0, b.unfinishedJobs());
        return ended;
    }

    static Map<Integer, Job> byId(List<Job> l) {
        Map<Integer, Job> m = new HashMap<>();
        for (Job j : l) m.put(j.id(), j);
        return m;
    }

    static final List<JobRecord> THREE = List.of(job(1, 0, 8, 100), job(2, 10, 4, 50), job(3, 20, 4, 30));

    @Test
    void eventDrivenQueuesUntilGpusFreeThenStartsBoth() {
        Map<Integer, Job> j = byId(run(ONE_SERVER, THREE, Triggers.eventDriven()));
        // job 1 holds all 8 GPUs 0-100; jobs 2 and 3 (4 GPUs each) both start at 100
        assertEquals(100, j.get(1).finishTime(), TOL);
        assertEquals(150, j.get(2).finishTime(), TOL);
        assertEquals(130, j.get(3).finishTime(), TOL);
        assertEquals(100, j.get(2).firstStartTime(), TOL);
        assertEquals(1, j.get(2).starts());
        assertEquals(new ResourceVector(4, 12, 250), j.get(2).lastAllocation().total());   // fixed share: 3 cores, 62.5 GB per GPU
    }

    @Test
    void periodicRoundsMakeJobsWaitForTheNextRound() {
        Map<Integer, Job> j = byId(run(ONE_SERVER, THREE, Triggers.periodic(300)));
        // job 1 arrives at 0, placed by the round at 0; jobs 2 and 3 wait for the round at 300
        assertEquals(100, j.get(1).finishTime(), TOL);
        assertEquals(300, j.get(2).firstStartTime(), TOL);
        assertEquals(350, j.get(2).finishTime(), TOL);
        assertEquals(330, j.get(3).finishTime(), TOL);
    }

    @Test
    void fifoLetsASmallerLaterJobUseGpusTheHeadCannot() {
        // 8 GPUs: job 1 holds 4; job 2 needs 8 (cannot fit), job 3 needs 4 (fits) and runs beside job 1
        List<JobRecord> jobs = List.of(job(1, 0, 4, 100), job(2, 1, 8, 10), job(3, 2, 4, 10));
        Map<Integer, Job> j = byId(run(ONE_SERVER, jobs, Triggers.eventDriven()));
        assertEquals(2, j.get(3).firstStartTime(), TOL);
        assertEquals(100, j.get(2).firstStartTime(), TOL);
        assertEquals(110, j.get(2).finishTime(), TOL);
    }

    @Test
    void consolidatedPlacementFillsTheFullestServerFirst() {
        ClusterSpec two = new ClusterSpec(List.of(new ServerGroup("v100", 2, 8, "V100", 24, 500, 500, 40)));
        List<JobRecord> jobs = List.of(job(1, 0, 4, 100), job(2, 1, 2, 100), job(3, 2, 8, 100));
        Map<Integer, Job> j = byId(run(two, jobs, Triggers.eventDriven()));
        Allocation a1 = j.get(1).lastAllocation(), a2 = j.get(2).lastAllocation(), a3 = j.get(3).lastAllocation();
        assertEquals(a1.parts().get(0).serverIndex(), a2.parts().get(0).serverIndex(), "job 2 joins job 1's server");
        assertNotEquals(a1.parts().get(0).serverIndex(), a3.parts().get(0).serverIndex(), "the 8-GPU job gets the empty server");
        assertEquals(2, j.get(3).firstStartTime(), TOL);
    }

    @Test
    void jobsLargerThanAnyServerAreRejectedBeforeTheRun() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> run(ONE_SERVER, List.of(job(1, 0, 16, 10)), Triggers.eventDriven()));
        assertTrue(e.getMessage().contains("16") || e.getMessage().contains("more than 8"), e.getMessage());
    }

    @Test
    void planDiffStartsResizesStopsAndLeavesUnchangedJobsAlone() {
        Allocation a = Allocation.single(0, new ResourceVector(1, 3, 62.5));
        Allocation b = Allocation.single(0, new ResourceVector(1, 9, 62.5));
        Map<Integer, Allocation> running = Map.of(1, a, 2, a, 3, a);
        Map<Integer, Allocation> planned = Map.of(1, a, 2, b, 4, a);
        List<PlanDiff.Change> d = PlanDiff.diff(running, planned);
        assertEquals(List.of(
                new PlanDiff.Change(2, PlanDiff.Kind.RESIZE, b),
                new PlanDiff.Change(3, PlanDiff.Kind.STOP, null),
                new PlanDiff.Change(4, PlanDiff.Kind.START, a)), d);
    }
}
