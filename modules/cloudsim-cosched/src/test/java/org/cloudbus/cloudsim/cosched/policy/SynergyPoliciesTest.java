package org.cloudbus.cloudsim.cosched.policy;

import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.model.ProfileTableModel;
import org.cloudbus.cloudsim.cosched.policy.PolicyFixtures.J;
import org.cloudbus.cloudsim.cosched.policy.PolicyFixtures.State;
import org.cloudbus.cloudsim.cosched.policy.impl.ConsolidatedPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FirstFitPlacement;
import org.cloudbus.cloudsim.cosched.policy.impl.FtfOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.LasOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.SrtfOrdering;
import org.cloudbus.cloudsim.cosched.policy.impl.SynergyGreedyAllocation;
import org.cloudbus.cloudsim.cosched.policy.impl.SynergyTune;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.cloudbus.cloudsim.cosched.policy.PolicyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 policies against hand-worked cases. Each case follows the code path in Synergy's simulator
 * named in docs/validation-plan.md, section 1.
 */
class SynergyPoliciesTest {

    static final ProfileTableModel TM = twoPoint();

    // ---- ideal demand (jobs/workload.py add_synergy_profile) ----------------------------------------

    @Test
    void idealDemandIsCappedAtOneServerWithReinterpolatedSpeedup() {
        // alexnet: 12 cores, 125 GB per GPU, speedup 2.94. 4 GPUs want 48 cores > 24: capped, speedup on the
        // straight line from the fixed share (12 cores, 1.0) to the ideal (48 cores, 2.94), at 24 cores.
        var ip = TM.idealPoint(new J(1, "alexnet", 4), V100);
        assertEquals(rv(4, 24, 500), ip.demand());
        assertEquals(1 + (2.94 - 1) / (48 - 12) * (24 - 12), ip.speedup(), 1e-12);
        assertEquals(ip.speedup(), TM.speedup(new J(1, "alexnet", 4), rv(4, 24, 500), V100), 1e-12);
        assertEquals(1.0, TM.speedup(new J(1, "alexnet", 4), rv(4, 12, 250), V100));
    }

    @Test
    void jobsLargerThanAServerAskForTheFixedShareAtSpeedupOne() {
        var ip = TM.idealPoint(new J(1, "res18", 16), V100);
        assertEquals(rv(16, 48, 1000), ip.demand());
        assertEquals(1.0, ip.speedup());
    }

    // ---- first fit (SYNERGY_RANDOM) -----------------------------------------------------------------

    @Test
    void firstFitTakesTheLowestNumberedServerNotTheFullest() {
        ClusterSpec two = servers(2);
        J running = new J(1, "gnmt", 4).running(Allocation.single(1, rv(4, 12, 250)));
        J job = new J(2, "res18", 1);
        RoundPlan p = plan(two, RoundPlan.Start.CARRY_RUNNING, running, job);
        assertEquals(0, new FirstFitPlacement(true).place(job, g -> g.proportionalShare(1), p).parts().get(0).serverIndex());
        assertEquals(1, new ConsolidatedPlacement(true).place(job, g -> g.proportionalShare(1), p).parts().get(0).serverIndex());
    }

    @Test
    void softFirstFitSplitsAJobInProportionToItsGpus() {
        ClusterSpec two = servers(2);
        J running = new J(1, "gnmt", 5).running(Allocation.single(0, rv(5, 15, 312.5)));
        J job = new J(2, "res18", 6);
        RoundPlan p = plan(two, RoundPlan.Start.CARRY_RUNNING, running, job);
        Allocation soft = new FirstFitPlacement(true).place(job, g -> g.proportionalShare(6), p);
        assertEquals(List.of(new Allocation.Part(0, rv(3, 9, 187.5)), new Allocation.Part(1, rv(3, 9, 187.5))), soft.parts());
        Allocation strict = new FirstFitPlacement(false).place(job, g -> g.proportionalShare(6), p);
        assertEquals(List.of(new Allocation.Part(1, rv(6, 18, 375))), strict.parts());
    }

    @Test
    void firstFitCountsGpusThatStillHaveTheirShareOfCores() {
        // server 0: 7 GPUs free but only 3 cores; a 9-core-per-GPU demand fits on no GPU there
        J hog = new J(1, "gnmt", 1).running(Allocation.single(0, rv(1, 21, 10)));
        J job = new J(2, "res18", 2);
        RoundPlan p = plan(servers(2), RoundPlan.Start.CARRY_RUNNING, hog, job);
        Allocation a = new FirstFitPlacement(true).place(job, g -> rv(2, 18, 125), p);
        assertEquals(List.of(new Allocation.Part(1, rv(2, 18, 125))), a.parts());
    }

    @Test
    void softConsolidatedSpreadsFromTheFullestServersWhenNoneFitsWhole() {
        ClusterSpec three = servers(3);
        J a = new J(1, "gnmt", 6).running(Allocation.single(0, rv(6, 18, 375)));
        J b = new J(2, "gnmt", 5).running(Allocation.single(1, rv(5, 15, 312.5)));
        J c = new J(3, "gnmt", 2).running(Allocation.single(2, rv(2, 6, 125)));
        J job = new J(4, "res18", 7);
        RoundPlan p = plan(three, RoundPlan.Start.CARRY_RUNNING, a, b, c, job);
        // free GPUs: 2, 3, 6; nothing holds 7. Take one at a time from the fullest: server 0 (2), then 1 (3), then 2.
        Allocation got = new ConsolidatedPlacement(true).place(job, g -> g.proportionalShare(7), p);
        assertEquals(List.of(new Allocation.Part(0, rv(2, 6, 125)), new Allocation.Part(1, rv(3, 9, 187.5)),
                new Allocation.Part(2, rv(2, 6, 125))), got.parts());
        assertNull(new ConsolidatedPlacement(false).place(job, g -> g.proportionalShare(7), p));
    }

    // ---- Greedy and Tune -------------------------------------------------------------------------------

    @Test
    void greedyAsksForTheIdealAndSkipsWhatDoesNotFit() {
        J a = new J(1, "res18", 1), b = new J(2, "res18", 1), c = new J(3, "res18", 1);
        RoundPlan p = plan(servers(1), RoundPlan.Start.EMPTY, a, b, c);
        var greedy = new SynergyGreedyAllocation();
        var place = new FirstFitPlacement(true);
        for (J j : List.of(a, b)) assertTrue(p.assign(j.id(), place.place(j, g -> greedy.demand(j, g, TM), p)));
        assertNull(place.place(c, g -> greedy.demand(c, g, TM), p), "third res18 needs 9 cores, 6 left");
    }

    /**
     * Five 1-GPU res18 jobs on one 24-core server, Synergy-Tune with first fit:
     * 1, 2 get their ideal 9 cores; 3 and 4 do not fit at 9 and are dropped to the fixed share (3 cores);
     * 5 fits neither way, so Tune shrinks the peer with most cores per GPU, job 1, to the fixed share and
     * places 5 at the fixed share. Only job 2 keeps its ideal demand.
     */
    @Test
    void tuneDropsToTheFixedShareThenShrinksTheRichestPeer() {
        List<J> jobs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) jobs.add(new J(i, "res18", 1));
        RoundPlan p = plan(servers(1), RoundPlan.Start.EMPTY, jobs.toArray(new JobInfo[0]));
        SynergyTune tune = new SynergyTune();
        FirstFitPlacement ff = new FirstFitPlacement(true);
        for (J j : jobs) assertTrue(p.assign(j.id(), tune.place(j, p, ff, TM)));
        assertEquals(rv(1, 3, 62.5), p.get(1).total());
        assertEquals(rv(1, 9, 62.5), p.get(2).total());
        for (int i = 3; i <= 5; i++) assertEquals(rv(1, 3, 62.5), p.get(i).total(), "job " + i);
        assertEquals(2.4, TM.speedup(jobs.get(1), p.get(2).total(), V100));
        assertEquals(1.0, TM.speedup(jobs.get(0), p.get(1).total(), V100));
    }

    @Test
    void tuneSkipsAJobWhenTheClusterHasTooFewFreeGpus() {
        List<JobInfo> jobs = new ArrayList<>();
        for (int i = 1; i <= 9; i++) jobs.add(new J(i, "gnmt", 1));
        RoundPlan p = plan(servers(1), RoundPlan.Start.EMPTY, jobs.toArray(new JobInfo[0]));
        SynergyTune tune = new SynergyTune();
        for (int i = 0; i < 8; i++) assertTrue(p.assign(i + 1, tune.place(jobs.get(i), p, new FirstFitPlacement(true), TM)));
        assertNull(tune.place(jobs.get(8), p, new FirstFitPlacement(true), TM));
    }

    // ---- orderings -------------------------------------------------------------------------------------

    static List<Integer> ids(List<JobInfo> l) {
        return l.stream().map(JobInfo::id).toList();
    }

    @Test
    void srtfOrdersByRemainingTimeAtTheChosenSpeed() {
        // res18 waiting: ideal speedup 2.4; gnmt: speedup 1
        J res = new J(1, "res18", 1).remaining(2000);     // 2000 s at the fixed share, 833 s at its ideal
        J gn = new J(2, "gnmt", 1).remaining(1000);
        State s = new State(0, servers(1), List.of(res, gn));
        assertEquals(List.of(2, 1), ids(new SrtfOrdering(RemainingEstimate.FIXED_SHARE).order(List.of(res, gn), s, TM)));
        assertEquals(List.of(1, 2), ids(new SrtfOrdering(RemainingEstimate.CURRENT_OR_IDEAL).order(List.of(res, gn), s, TM)));
        // running at the fixed share last round: Synergy estimates it at speedup 1 again
        res.running(Allocation.single(0, rv(1, 3, 62.5)));
        assertEquals(List.of(2, 1), ids(new SrtfOrdering(RemainingEstimate.CURRENT_OR_IDEAL).order(List.of(res, gn), s, TM)));
    }

    @Test
    void lasOrdersByAttainedGpuSecondsThenArrival() {
        J a = new J(1, "gnmt", 1).attained(600), b = new J(2, "gnmt", 4).attained(300), c = new J(3, "gnmt", 1).attained(300);
        State s = new State(0, servers(1), List.of(a, b, c));
        assertEquals(List.of(2, 3, 1), ids(new LasOrdering().order(List.of(a, b, c), s, TM)));
    }

    @Test
    void ftfOrdersByFinishTimeFairness() {
        // (now - arrival + remaining) / whole run time: job 1 (1000 + 500) / 1000 = 1.5; job 2 (100 + 900) / 2000 = 0.5
        J a = new J(1, "gnmt", 1).arrival(0).remaining(500).total(1000);
        J b = new J(2, "gnmt", 1).arrival(900).remaining(900).total(2000);
        State s = new State(1000, servers(1), List.of(a, b));
        assertEquals(List.of(2, 1), ids(new FtfOrdering(RemainingEstimate.FIXED_SHARE).order(List.of(a, b), s, TM)));
    }

    @Test
    void ftfAlsoBoostsStarvedJobs() {
        double hours1001 = 1001 * 3600;
        // job 1 is starved and has the worse (higher) fairness metric, job 2 the better one
        J starved = new J(1, "gnmt", 1).arrival(0).remaining(500).total(1000);
        J fresh = new J(2, "gnmt", 1).arrival(hours1001 - 10).remaining(500).total(1000);
        FtfOrdering ftf = new FtfOrdering(RemainingEstimate.FIXED_SHARE);
        State s = new State(hours1001, servers(1), List.of(starved, fresh));
        assertEquals(List.of(2, 1), ids(ftf.order(List.of(starved, fresh), s, TM)));
        assertEquals(List.of(1, 2), ids(ftf.order(List.of(starved, fresh), s, TM)), "boosted from the next round");
    }

    @Test
    void starvedJobsSortFirstFromTheNextRoundOn() {
        double hours1001 = 1001 * 3600;
        J starved = new J(1, "gnmt", 1).arrival(0).attained(10_000);
        J fresh = new J(2, "gnmt", 1).arrival(hours1001 - 10).attained(0);
        LasOrdering las = new LasOrdering();
        State s = new State(hours1001, servers(1), List.of(starved, fresh));
        assertEquals(List.of(2, 1), ids(las.order(List.of(starved, fresh), s, TM)), "this round: marked, not yet boosted");
        assertEquals(List.of(1, 2), ids(las.order(List.of(starved, fresh), s, TM)), "next round: boosted first");
    }
}
