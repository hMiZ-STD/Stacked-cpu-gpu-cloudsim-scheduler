package org.cloudbus.cloudsim.cosched.broker;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.cosched.core.Allocation;
import org.cloudbus.cloudsim.cosched.core.ClusterSpec;
import org.cloudbus.cloudsim.cosched.core.JobInfo;
import org.cloudbus.cloudsim.cosched.core.Organisation;
import org.cloudbus.cloudsim.cosched.core.RoundPlan;
import org.cloudbus.cloudsim.cosched.core.Server;
import org.cloudbus.cloudsim.cosched.core.ServerGroup;
import org.cloudbus.cloudsim.cosched.model.RateCalculator;
import org.cloudbus.cloudsim.cosched.workload.JobRecord;
import org.cloudbus.cloudsim.gpu.api.GpuLayer;
import org.cloudbus.cloudsim.gpu.api.JobCommand;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The scheduler's event loop. Jobs arrive from the workload; each scheduling round it builds a
 * {@link RoundPlan} with the candidate's plug-ins (lifecycle, ordering, allocation, placement),
 * turns the plan into Start / Resize / Stop commands with each job's speed, and hands them to the
 * GPU layer. Policies only see a read-only {@link org.cloudbus.cloudsim.cosched.core.ClusterState}.
 *
 * <p>Events: job arrival and scheduling round (its own); job end comes from the GPU layer's
 * listener. The run ends when no job is left and none is still to arrive; the broker then simply
 * stops scheduling (contract rule 6: it never ends the simulation itself).
 */
public final class CoSchedulingBroker extends SimEntity {

    enum Tag implements CloudSimTags { JOB_ARRIVAL, SCHEDULE_ROUND }

    private enum RoundKind { PERIODIC, EVENT }

    private final GpuLayer layer;
    private final ClusterSpec spec;
    private final List<Server> servers;
    private final Organisation organisation;
    private final List<JobRecord> workload;
    private final SchedulerSetup setup;
    private final RateCalculator rates;
    private final Consumer<Job> onJobEnd;

    private final Map<Integer, Job> active = new LinkedHashMap<>();   // arrival order
    private final List<Job> finished = new ArrayList<>();
    private PlanExecutor executor;
    private int nextArrival;
    private boolean eventRoundPending;
    private int rounds;
    private long commandsApplied;

    public CoSchedulingBroker(String name, GpuLayer layer, ClusterSpec spec, Organisation organisation,
                              List<JobRecord> labelledJobs, SchedulerSetup setup, RateCalculator rates,
                              Consumer<Job> onJobEnd) {
        super(name);
        this.layer = layer;
        this.spec = spec;
        this.servers = spec.servers();
        this.organisation = organisation;
        this.workload = List.copyOf(labelledJobs);
        this.setup = setup;
        this.rates = rates;
        this.onJobEnd = onJobEnd;
        checkPlaceable();
    }

    /** STRICT locality cannot place a job needing more GPUs than any one server has; say so before the run. */
    private void checkPlaceable() {
        int maxPerServer = spec.serverGroups().stream().mapToInt(ServerGroup::gpus).max().orElse(0);
        int limit = setup.placement().maxPlaceableGpus(maxPerServer, spec.totalCapacity().gpus());
        long tooBig = workload.stream().filter(j -> j.gpus() > limit).count();
        if (tooBig > 0)
            throw new IllegalArgumentException(tooBig + " job(s) need more than " + limit + " GPUs, which this placement can never "
                    + "place (largest server: " + maxPerServer + " GPUs). Filter them out (tools/prep_philly.py --strict) or use SOFT locality.");
    }

    @Override
    public void startEntity() {
        executor = layer.bind(this);
        executor.setJobEndListener(this::jobEnded);
        if (!workload.isEmpty()) schedule(getId(), workload.get(0).arrivalS(), Tag.JOB_ARRIVAL, 0);
        if (setup.trigger().roundS() > 0) schedule(getId(), 0, Tag.SCHEDULE_ROUND, RoundKind.PERIODIC);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (executor.handle(ev)) return;
        if (ev.getTag() == Tag.JOB_ARRIVAL) arrive((Integer) ev.getData());
        else if (ev.getTag() == Tag.SCHEDULE_ROUND) round((RoundKind) ev.getData());
    }

    @Override
    public void shutdownEntity() {
    }

    // ---- events ---------------------------------------------------------------------------

    private void arrive(int index) {
        JobRecord r = workload.get(index);
        double iterTime = r.iterTimeS() != null ? r.iterTimeS()
                : r.durationS() != null ? 1.0 : rates.throughput().iterationTime(probe(r), firstGroup());
        double work = r.iterations() != null ? r.iterations() : r.durationS();   // duration jobs: 1 work unit = 1 s at the fixed share
        Job j = new Job(r, work, iterTime, id -> executor.remainingWork(id), CloudSim::clock);
        nextArrival = index + 1;
        if (nextArrival < workload.size())
            schedule(getId(), workload.get(nextArrival).arrivalS() - CloudSim.clock(), Tag.JOB_ARRIVAL, nextArrival);
        if (!admit(j)) return;
        active.put(j.id(), j);
        if (setup.trigger().onArrival()) requestRound();
    }

    /** Admission control seam: every job is accepted (no paper or trace we use needs rejection). */
    private boolean admit(Job job) {
        return true;
    }

    private void jobEnded(int jobId, double time) {
        Job j = active.remove(jobId);
        if (j == null) throw new IllegalStateException("GPU layer reported the end of unknown job " + jobId);
        j.finish(time);
        finished.add(j);
        onJobEnd.accept(j);
        if (setup.trigger().onJobEnd()) requestRound();
    }

    private void requestRound() {
        if (eventRoundPending) return;
        eventRoundPending = true;
        schedule(getId(), 0, Tag.SCHEDULE_ROUND, RoundKind.EVENT);
    }

    // ---- a scheduling round -----------------------------------------------------------------

    private void round(RoundKind kind) {
        if (kind == RoundKind.EVENT) eventRoundPending = false;
        double now = CloudSim.clock();
        if (!active.isEmpty()) schedulePlan(now);
        if (kind == RoundKind.PERIODIC) scheduleNextPeriodicRound(now);
    }

    private void schedulePlan(double now) {
        rounds++;
        List<JobInfo> jobs = new ArrayList<>(active.values());
        StateSnapshot state = new StateSnapshot(now, spec, servers, organisation, jobs);
        RoundPlan plan = new RoundPlan(state, setup.lifecycle().planStart());

        List<JobInfo> toPlace = new ArrayList<>();
        for (JobInfo j : jobs) if (!plan.contains(j.id())) toPlace.add(j);
        for (JobInfo j : setup.ordering().order(toPlace, state)) {
            Allocation a = setup.placement().place(j, g -> setup.allocation().demand(j, g, rates.throughput()), plan);
            if (a != null && !plan.assign(j.id(), a))
                throw new IllegalStateException("placement returned an allocation for job " + j.id() + " that does not fit the plan");
        }

        Map<Integer, Allocation> running = new LinkedHashMap<>();
        for (Job j : active.values()) if (j.allocation() != null) running.put(j.id(), j.allocation());
        List<PlanDiff.Change> changes = PlanDiff.diff(running, plan.entries());
        if (changes.isEmpty()) return;

        List<JobCommand> cmds = new ArrayList<>(changes.size());
        for (PlanDiff.Change c : changes) {
            Job j = active.get(c.jobId());
            switch (c.kind()) {
                case STOP -> cmds.add(new JobCommand.Stop(j.id()));
                case START -> {
                    RateCalculator.Speed s = rates.speed(j, c.to(), j.iterTimeS());
                    cmds.add(new JobCommand.Start(j.id(), j.remainingWork(), parts(c.to()), s.rate(), s.gpuBusyFraction()));
                }
                case RESIZE -> {
                    RateCalculator.Speed s = rates.speed(j, c.to(), j.iterTimeS());
                    cmds.add(new JobCommand.Resize(j.id(), parts(c.to()), s.rate(), s.gpuBusyFraction()));
                }
            }
        }
        executor.apply(cmds);
        commandsApplied += cmds.size();
        for (PlanDiff.Change c : changes) active.get(c.jobId()).setAllocation(c.to(), now);
    }

    private void scheduleNextPeriodicRound(double now) {
        double r = setup.trigger().roundS();
        if (active.isEmpty() && nextArrival >= workload.size()) return;    // nothing left: the run ends
        double next = now + r;
        if (active.isEmpty()) {                                               // idle: skip to the round at or after the next arrival
            double arrival = workload.get(nextArrival).arrivalS();
            next = Math.max(next, Math.ceil(arrival / r - 1e-12) * r);
        }
        schedule(getId(), next - now, Tag.SCHEDULE_ROUND, RoundKind.PERIODIC);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static List<PartSpec> parts(Allocation a) {
        List<PartSpec> l = new ArrayList<>(a.parts().size());
        for (Allocation.Part p : a.parts())
            l.add(new PartSpec(p.serverIndex(), p.resources().gpus(), p.resources().cpus(), p.resources().memGb()));
        return l;
    }

    private ServerGroup firstGroup() {
        return spec.serverGroups().get(0);
    }

    /** A minimal JobInfo for looking up a job's profile before the Job exists. */
    private static JobInfo probe(JobRecord r) {
        return new Job(r, 1, 1, id -> 1, () -> 0);
    }

    public List<Job> finishedJobs() {
        return Collections.unmodifiableList(finished);
    }

    public int unfinishedJobs() {
        return active.size() + (workload.size() - nextArrival);
    }

    public int rounds() {
        return rounds;
    }

    public long commandsApplied() {
        return commandsApplied;
    }
}
