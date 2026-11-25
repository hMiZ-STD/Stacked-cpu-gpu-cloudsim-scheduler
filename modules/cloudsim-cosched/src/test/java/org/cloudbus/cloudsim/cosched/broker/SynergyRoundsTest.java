package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.InterferenceModel;
import org.cloudbus.cloudsim.cosched.model.LocalityModel;
import org.cloudbus.cloudsim.cosched.model.ProfileRepository;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;
import org.cloudbus.cloudsim.cosched.model.RateCalculator;
import org.cloudbus.cloudsim.cosched.policy.impl.FifoOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.FirstFitPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.GpuProportionalAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.RoundBasedLifecycle;
import org.cloudbus.cloudsim.cosched.policy.impl.SynergyTune;
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

/**
 * Round-based scheduling end to end, with and without Synergy's round semantics. res18: 0.64 s per
 * iteration at the fixed share, so floor(300 / 0.64) = 468 iterations per round; at its ideal speedup 2.4,
 * floor(300 / (0.64 / 2.4)) = 1125 (both computed in Python as Synergy does).
 */
class SynergyRoundsTest {

    static final ClusterSpec ONE = new ClusterSpec(List.of(new ServerGroup("v100", 1, 8, "V100", 24, 500, 500, 40)));

    static JobRecord res18(int id, double arrival, double iterations) {
        return new JobRecord(id, arrival, 1, iterations, 0.64, null, "res18", null, null, id + 2);
    }

    static Map<Integer, Job> run(List<JobRecord> jobs, boolean tune, boolean synergyRounds) {
        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        CloudSimGpuLayer layer = new CloudSimGpuLayer();
        List<ServerShape> shapes = new ArrayList<>();
        for (var s : ONE.servers())
            shapes.add(new ServerShape(s.index(), "v100", 8, "V100", 24, 500, 500, 40));
        layer.build(shapes);
        ProfileRepository repo = ProfileRepository.load(Path.of("../../presets/profiles/synergy-v100/models"), ProfileRepository.Scaling.LINEAR);
        RateCalculator rates = new RateCalculator(new ProfileTableModel(repo, ProfileTableModel.Mode.SYNERGY_TWO_POINT),
                new LocalityModel(1.0), InterferenceModel.NONE, ONE);
        SchedulerSetup setup = new SchedulerSetup(Triggers.periodic(300), new FifoOrdering(),
                tune ? null : new GpuProportionalAllocation(), tune ? new SynergyTune() : null,
                new FirstFitPlacement(true), new RoundBasedLifecycle(synergyRounds));
        List<Job> ended = new ArrayList<>();
        CoSchedulingBroker b = new CoSchedulingBroker("broker", layer, ONE, Organisation.single(), jobs, setup, rates, ended::add);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        assertEquals(0, b.unfinishedJobs());
        Map<Integer, Job> m = new HashMap<>();
        for (Job j : ended) m.put(j.id(), j);
        return m;
    }

    @Test
    void synergyRoundsAdvanceWholeIterationsAndFinishAtTheRoundEnd() {
        // fixed share: 468 per round; 1000 iterations need 3 rounds -> finish at 900 s (continuous: 640 s)
        assertEquals(900, run(List.of(res18(1, 0, 1000)), false, true).get(1).finishTime(), 1e-6);
        assertEquals(640, run(List.of(res18(1, 0, 1000)), false, false).get(1).finishTime(), 0.05);
    }

    @Test
    void tuneRunsAtTheIdealSpeedupQuantisedPerRound() {
        // ideal: 1125 per round; 3000 iterations -> round 3 -> 900 s. Continuous: 3000 x 0.64 / 2.4 = 800 s.
        assertEquals(900, run(List.of(res18(1, 0, 3000)), true, true).get(1).finishTime(), 1e-6);
        assertEquals(800, run(List.of(res18(1, 0, 3000)), true, false).get(1).finishTime(), 0.05);
    }

    @Test
    void aJobEndingExactlyOnARoundBoundaryFinishesThere() {
        // 936 = 2 x 468: done at exactly 600 s, the end of round 2, and not carried into round 3
        Job j = run(List.of(res18(1, 0, 936)), false, true).get(1);
        assertEquals(600, j.finishTime(), 1e-6);
        assertEquals(1, j.starts());
    }

    @Test
    void arrivalsWaitForTheNextRound() {
        // arrives at 10 s, first round after that is 300 s
        Job j = run(List.of(res18(1, 10, 468)), false, true).get(1);
        assertEquals(300, j.firstStartTime(), 1e-6);
        assertEquals(600, j.finishTime(), 1e-6);
    }

    /** Found replaying Synergy at 6 jobs/hr: a job arriving exactly on a round boundary joins that round. */
    @Test
    void anArrivalExactlyOnARoundBoundaryJoinsThatRound() {
        // job 1 arrives at 310, after the 600 s round was queued (at 300); job 2's arrival at 600 is queued only
        // then, so CloudSim would run the round first. Job 2 must still join the round at 600.
        Map<Integer, Job> j = run(List.of(res18(1, 310, 4680), res18(2, 600, 468)), false, true);
        assertEquals(600, j.get(2).firstStartTime(), 1e-6);
        assertEquals(900, j.get(2).finishTime(), 1e-6);
        assertEquals(1, j.get(2).starts());
    }

    @Test
    void synergyRoundsRefusesEventDrivenRounds() {
        assertThrows(IllegalArgumentException.class, () -> new SchedulerSetup(Triggers.eventDriven(), new FifoOrdering(),
                new GpuProportionalAllocation(), new FirstFitPlacement(true), new RoundBasedLifecycle(true)));
    }
}
