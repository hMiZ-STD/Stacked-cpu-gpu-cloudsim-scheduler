package org.cloudbus.cloudsim.gpu;

import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.gpu.api.JobCommand;
import org.cloudbus.cloudsim.gpu.api.JobEndListener;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The GPU layer's executor. Applies one round's commands in three phases so that the order of
 * commands never matters and capacity freed in a round is reusable in the same round:
 *
 * <ol>
 *   <li>credit all progress at the old speeds (rule 1);</li>
 *   <li>release: stops, moves (a resize to other servers or GPU counts), and the old cores and
 *       memory of every in-place resize;</li>
 *   <li>reserve: new sizes of in-place resizes, then starts (including moved jobs).</li>
 * </ol>
 *
 * Reservations on {@link GpuHost} are made here, synchronously; CloudSim's guest creation and
 * destruction events follow and only carry the guests. Any reservation that does not fit throws
 * (rule 5). After applying, a datacenter update is scheduled at each changed job's new predicted
 * finish (rule 3), because CloudSim ignores a "wake now" sent within 0.01 s of its last update.
 */
public class GpuPlanExecutor implements PlanExecutor {

    private final SimEntity broker;
    private final int dcId;
    private final List<GpuHost> hosts;
    private final PlannedGuestAllocationPolicy policy;

    private final Map<Integer, TrainingJob> running = new HashMap<>();
    private final Map<Integer, Double> stoppedRemaining = new HashMap<>();
    private final Map<Integer, GpuVm> liveGuests = new HashMap<>();      // guest id -> guest of a running job
    private int nextGuestId = 0;
    private JobEndListener listener = (id, t) -> { };

    GpuPlanExecutor(SimEntity broker, int dcId, List<GpuHost> hosts, PlannedGuestAllocationPolicy policy) {
        this.broker = broker;
        this.dcId = dcId;
        this.hosts = hosts;
        this.policy = policy;
    }

    @Override
    public void setJobEndListener(JobEndListener listener) {
        this.listener = listener;
    }

    /** Running job as the GPU layer sees it, or null. */
    public TrainingJob job(int jobId) {
        return running.get(jobId);
    }

    @Override
    public void apply(List<JobCommand> commands) {
        if (commands.isEmpty()) return;
        double now = CloudSim.clock();
        creditAll(now);                                                         // rule 1

        List<JobCommand.Resize> inPlace = new ArrayList<>();
        List<JobCommand.Start> starts = new ArrayList<>();
        for (JobCommand c : commands) {                                         // phase: release
            switch (c) {
                case JobCommand.Stop s -> stop(s.jobId());
                case JobCommand.Resize r -> {
                    TrainingJob job = requireRunning(r.jobId());
                    if (sameServersAndGpus(job, r.parts())) {
                        for (GpuVm vm : job.parts()) host(vm).releaseCoresAndMemory(vm);
                        inPlace.add(r);
                    } else {
                        double rem = stop(r.jobId());
                        starts.add(new JobCommand.Start(r.jobId(), rem, r.parts(), r.rate(), r.gpuBusyFraction()));
                    }
                }
                case JobCommand.Start s -> starts.add(s);
            }
        }
        for (JobCommand.Resize r : inPlace) resizeInPlace(now, r);              // phase: reserve
        for (JobCommand.Start s : starts) start(s);

        for (JobCommand.Resize r : inPlace) wakeAtFinish(running.get(r.jobId()));
        for (JobCommand.Start s : starts) wakeAtFinish(running.get(s.jobId()));
    }

    @Override
    public double remainingWork(int jobId) {
        Double stopped = stoppedRemaining.get(jobId);
        if (stopped != null) return stopped;
        TrainingJob job = running.get(jobId);
        if (job == null) throw new IllegalArgumentException("job " + jobId + " is unknown to the GPU layer");
        creditAll(CloudSim.clock());
        return job.remainingWork();
    }

    @Override
    public boolean handle(SimEvent ev) {
        if (ev.getTag() == CloudActionTags.VM_CREATE_ACK && ev.getData() instanceof int[] d) {
            policy.forget(d[1]);
            GpuVm vm = liveGuests.get(d[1]);
            if (vm != null && d[2] != CloudSimTags.TRUE)                       // rule 5
                throw new IllegalStateException("server " + vm.spec().serverIndex() + " refused the guest of job " + vm.jobId());
            return true;                                                        // refusals of already-stopped guests are expected
        }
        if (ev.getTag() == CloudActionTags.CLOUDLET_RETURN && ev.getData() instanceof TrainingCloudlet cl) {
            GpuVm vm = liveGuests.get(cl.getGuestId());
            if (vm == null) return true;                                        // part of a stopped job
            TrainingJob job = running.get(vm.jobId());
            int i = job.indexOf(vm);
            if (job.cloudlet(i) != cl) return true;
            job.markDone(i);
            liveGuests.remove(vm.getId());
            host(vm).release(vm);
            broker.schedule(dcId, 0, CloudActionTags.VM_DESTROY, vm);
            if (job.allDone()) {
                running.remove(job.jobId());
                listener.jobEnded(job.jobId(), CloudSim.clock());
            }
            return true;
        }
        return ev.getTag() == CloudActionTags.VM_DESTROY_ACK;
    }

    // ---- commands -------------------------------------------------------------------------

    private void start(JobCommand.Start s) {
        if (running.containsKey(s.jobId())) throw new IllegalStateException("job " + s.jobId() + " is already running");
        TrainingJob job = new TrainingJob(s.jobId(), s.rate());
        List<GpuVm> reservedSoFar = new ArrayList<>();
        for (PartSpec ps : s.parts()) {
            int id = nextGuestId++;
            GpuVm vm = new GpuVm(id, broker.getId(), s.jobId(), ps, s.rate(), s.gpuBusyFraction());
            host(ps.serverIndex()).reserve(vm);                                 // throws if it does not fit (rule 5)
            reservedSoFar.add(vm);
            TrainingCloudlet cl = new TrainingCloudlet(id, s.jobId(), s.remainingWork(), broker.getId(), id);
            job.addPart(vm, cl);
            liveGuests.put(id, vm);
            policy.plan(id, ps.serverIndex());                                  // rule 4
            broker.schedule(dcId, 0, CloudActionTags.VM_CREATE_ACK, vm);
            // Submit right behind the create (same instant, processed in order). Waiting for the
            // ack would start the job late: CloudSim delays the ack by minTimeBetweenEvents.
            broker.schedule(dcId, 0, CloudActionTags.CLOUDLET_SUBMIT, cl);
        }
        stoppedRemaining.remove(s.jobId());
        running.put(s.jobId(), job);
    }

    /** Releases a running job's resources and keeps its remaining work. Progress must be credited. */
    private double stop(int jobId) {
        TrainingJob job = requireRunning(jobId);
        double rem = job.remainingWork();
        for (int i = 0; i < job.parts().size(); i++) {
            if (job.isDone(i)) continue;
            GpuVm vm = job.parts().get(i);
            liveGuests.remove(vm.getId());
            host(vm).release(vm);
            broker.schedule(dcId, 0, CloudActionTags.VM_DESTROY, vm);
        }
        running.remove(jobId);
        stoppedRemaining.put(jobId, rem);
        return rem;
    }

    private void resizeInPlace(double now, JobCommand.Resize r) {
        TrainingJob job = running.get(r.jobId());
        for (GpuVm vm : job.parts()) {
            PartSpec next = partOn(r.parts(), vm.spec().serverIndex());
            host(vm).reserveCoresAndMemory(vm, next, r.gpuBusyFraction());
            vm.setSpec(next);
            vm.setGpuBusyFraction(r.gpuBusyFraction());
            vm.scheduler().setRate(now, r.rate());                              // credits, then switches
        }
        job.setRate(r.rate());
    }

    // ---- helpers --------------------------------------------------------------------------

    private void creditAll(double now) {
        for (TrainingJob job : running.values())
            for (GpuVm vm : job.parts()) vm.scheduler().credit(now);
    }

    private void wakeAtFinish(TrainingJob job) {
        double delay = job.remainingWork() / job.rate();
        broker.schedule(dcId, Math.max(delay, CloudSim.getMinTimeBetweenEvents()), CloudActionTags.VM_DATACENTER_EVENT, null);
    }

    private static boolean sameServersAndGpus(TrainingJob job, List<PartSpec> next) {
        if (next.size() != job.parts().size()) return false;
        for (GpuVm vm : job.parts()) {
            PartSpec n = partOnOrNull(next, vm.spec().serverIndex());
            if (n == null || n.gpus() != vm.spec().gpus()) return false;
        }
        return true;
    }

    private static PartSpec partOn(List<PartSpec> parts, int server) {
        PartSpec p = partOnOrNull(parts, server);
        if (p == null) throw new IllegalStateException("no part on server " + server);
        return p;
    }

    private static PartSpec partOnOrNull(List<PartSpec> parts, int server) {
        for (PartSpec p : parts) if (p.serverIndex() == server) return p;
        return null;
    }

    private GpuHost host(GpuVm vm) {
        return host(vm.spec().serverIndex());
    }

    private GpuHost host(int index) {
        if (index < 0 || index >= hosts.size()) throw new IllegalArgumentException("no server " + index);
        return hosts.get(index);
    }

    private TrainingJob requireRunning(int jobId) {
        TrainingJob j = running.get(jobId);
        if (j == null) throw new IllegalStateException("job " + jobId + " is not running");
        return j;
    }
}
