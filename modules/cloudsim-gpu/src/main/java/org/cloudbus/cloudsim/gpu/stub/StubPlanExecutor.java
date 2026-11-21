package org.cloudbus.cloudsim.gpu.stub;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.gpu.api.JobCommand;
import org.cloudbus.cloudsim.gpu.api.JobEndListener;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stand-in executor built from the Check 1 prototypes (CheckC / CheckE). Each job part is one
 * VM holding one cloudlet with the job's full remaining work; every part runs at the job's rate,
 * so a spread job's parts finish together. Job speed is carried as VM MIPS: 1 work unit = {@link #SCALE} MI.
 */
public class StubPlanExecutor implements PlanExecutor {

    /** MI per work unit (iteration). Large so the long cloudlet lengths keep sub-iteration precision. */
    static final double SCALE = 1e6;

    private final SimEntity broker;
    private final int dcId;
    private final List<StubGpuHost> hosts;
    private final PlannedGuestAllocationPolicy policy;

    private final Map<Integer, JobRun> jobs = new HashMap<>();          // running jobs
    private final Map<Integer, Double> stoppedRemaining = new HashMap<>();
    private final Map<Integer, Part> partsByVm = new HashMap<>();
    private int nextId = 0;
    private JobEndListener listener = (id, t) -> { };

    StubPlanExecutor(SimEntity broker, int dcId, List<StubGpuHost> hosts, PlannedGuestAllocationPolicy policy) {
        this.broker = broker;
        this.dcId = dcId;
        this.hosts = hosts;
        this.policy = policy;
    }

    private static final class Part {
        final JobRun job;
        final PartSpec spec;
        final Vm vm;
        final Cloudlet cloudlet;
        boolean created;   // create and cloudlet submit sent to the datacenter
        boolean done;

        Part(JobRun job, PartSpec spec, Vm vm, Cloudlet cloudlet) {
            this.job = job;
            this.spec = spec;
            this.vm = vm;
            this.cloudlet = cloudlet;
        }
    }

    private static final class JobRun {
        final int jobId;
        final double startWork;
        final List<Part> parts = new ArrayList<>();
        double rate;

        JobRun(int jobId, double startWork, double rate) {
            this.jobId = jobId;
            this.startWork = startWork;
            this.rate = rate;
        }
    }

    @Override
    public void setJobEndListener(JobEndListener listener) {
        this.listener = listener;
    }

    @Override
    public void apply(List<JobCommand> commands) {
        if (commands.isEmpty()) return;
        creditProgress();                                                   // rule 1
        boolean resized = false;
        for (JobCommand c : commands) if (c instanceof JobCommand.Stop s) stop(s.jobId());
        List<JobCommand.Start> moves = new ArrayList<>();
        for (JobCommand c : commands) {
            if (!(c instanceof JobCommand.Resize r)) continue;
            JobRun run = requireRunning(r.jobId());
            if (inPlace(run, r.parts())) {
                resizeInPlace(run, r.parts(), r.rate());
                resized = true;
            } else {                                                        // moved: stop, then start
                double rem = stop(r.jobId());
                moves.add(new JobCommand.Start(r.jobId(), rem, r.parts(), r.rate()));
            }
        }
        for (JobCommand.Start m : moves) start(m);
        for (JobCommand c : commands) if (c instanceof JobCommand.Start s) start(s);
        if (resized) broker.schedule(dcId, 0, CloudActionTags.VM_DATACENTER_EVENT, null);   // rule 3
    }

    @Override
    public double remainingWork(int jobId) {
        Double stopped = stoppedRemaining.get(jobId);
        if (stopped != null) return stopped;
        JobRun run = jobs.get(jobId);
        if (run == null) throw new IllegalArgumentException("job " + jobId + " is unknown to the GPU layer");
        creditProgress();
        return remaining(run);
    }

    @Override
    public boolean handle(SimEvent ev) {
        if (ev.getTag() == CloudActionTags.VM_CREATE_ACK && ev.getData() instanceof int[] d) {
            Part p = partsByVm.get(d[1]);
            if (p == null) {                                                // stopped before its create landed;
                policy.forget(d[1]);                                        // the queued destroy removes it
                return true;
            }
            if (d[2] != CloudSimTags.TRUE)                                  // rule 5
                throw new IllegalStateException("server " + p.spec.serverIndex() + " refused guest of job " + p.job.jobId);
            return true;
        }
        if (ev.getTag() == CloudActionTags.CLOUDLET_RETURN && ev.getData() instanceof Cloudlet c) {
            Part p = partsByVm.get(c.getGuestId());
            if (p == null || p.cloudlet != c) return true;                  // stale return of a stopped part
            p.done = true;
            partsByVm.remove(p.vm.getId());
            policy.forget(p.vm.getId());
            release(p);
            broker.schedule(dcId, 0, CloudActionTags.VM_DESTROY, p.vm);
            JobRun run = p.job;
            if (run.parts.stream().allMatch(x -> x.done)) {
                jobs.remove(run.jobId);
                listener.jobEnded(run.jobId, CloudSim.clock());
            }
            return true;
        }
        return ev.getTag() == CloudActionTags.VM_DESTROY_ACK;
    }

    // ---- commands -------------------------------------------------------------------------

    private void start(JobCommand.Start s) {
        if (jobs.containsKey(s.jobId())) throw new IllegalStateException("job " + s.jobId() + " is already running");
        stoppedRemaining.remove(s.jobId());
        JobRun run = new JobRun(s.jobId(), s.remainingWork(), s.rate());
        long length = Math.max(1, Math.round(s.remainingWork() * SCALE));
        for (PartSpec ps : s.parts()) {
            StubGpuHost host = host(ps.serverIndex());
            host.reserve(ps);                                               // throws on over-commit (rule 5)
            int id = nextId++;
            Vm vm = new Vm(id, broker.getId(), s.rate() * SCALE, 1, 1, 1, 1, "Xen", new CloudletSchedulerTimeShared());
            UtilizationModelFull full = new UtilizationModelFull();
            Cloudlet cl = new Cloudlet(id, length, 1, 0, 0, full, full, full);
            cl.setUserId(broker.getId());
            cl.setGuestId(id);
            Part p = new Part(run, ps, vm, cl);
            run.parts.add(p);
            partsByVm.put(id, p);
            policy.plan(id, ps.serverIndex());                              // rule 4
            broker.schedule(dcId, 0, CloudActionTags.VM_CREATE_ACK, vm);
            // Submit right behind the create: the datacenter handles both, in order, at this instant.
            // Waiting for the ack would start every job late, since CloudSim delays the ack by
            // minTimeBetweenEvents (0.01 s). The ack only checks for refusal (rule 5).
            broker.schedule(dcId, 0, CloudActionTags.CLOUDLET_SUBMIT, cl);
            p.created = true;
        }
        jobs.put(s.jobId(), run);
    }

    /** Stops a running job; returns and remembers its remaining work. Progress must already be credited. */
    private double stop(int jobId) {
        JobRun run = requireRunning(jobId);
        double rem = remaining(run);
        for (Part p : run.parts) {
            if (p.done) continue;
            partsByVm.remove(p.vm.getId());
            if (p.created) policy.forget(p.vm.getId());   // else the pending create still needs it; ack cleans up
            release(p);
            broker.schedule(dcId, 0, CloudActionTags.VM_DESTROY, p.vm);
        }
        jobs.remove(jobId);
        stoppedRemaining.put(jobId, rem);
        return rem;
    }

    private void resizeInPlace(JobRun run, List<PartSpec> newParts, double rate) {
        for (Part p : run.parts) host(p.spec.serverIndex()).release(p.spec);
        List<Part> rebuilt = new ArrayList<>();
        for (Part p : run.parts) {
            PartSpec ns = matching(newParts, p.spec.serverIndex());
            host(ns.serverIndex()).reserve(ns);
            double mips = rate * SCALE;
            p.vm.setMips(mips);
            HostEntity h = p.vm.getHost();
            if (h != null) {                                                    // else the queued create reads the new MIPS
                h.getGuestScheduler().deallocatePesForGuest(p.vm);              // rule 2
                if (!h.getGuestScheduler().allocatePesForGuest(p.vm, List.of(mips)))
                    throw new IllegalStateException("resize refused for job " + run.jobId);   // rule 5
            }
            Part np = new Part(run, ns, p.vm, p.cloudlet);
            np.created = p.created;
            rebuilt.add(np);
            partsByVm.put(p.vm.getId(), np);
        }
        run.parts.clear();
        run.parts.addAll(rebuilt);
        run.rate = rate;
    }

    // ---- helpers --------------------------------------------------------------------------

    private void creditProgress() {
        double now = CloudSim.clock();
        Set<HostEntity> seen = new HashSet<>();
        for (JobRun run : jobs.values())
            for (Part p : run.parts)
                if (p.created && !p.done && p.vm.getHost() != null && seen.add(p.vm.getHost()))
                    p.vm.getHost().updateCloudletsProcessing(now);
    }

    /** Work left: parts move in lock-step, so the first live, created part is representative. */
    private double remaining(JobRun run) {
        for (Part p : run.parts)
            if (!p.done) return p.cloudlet.getRemainingCloudletLength() / SCALE;
        return 0;
    }

    private boolean inPlace(JobRun run, List<PartSpec> newParts) {
        if (newParts.size() != run.parts.size()) return false;
        for (Part p : run.parts) {
            PartSpec ns = matchingOrNull(newParts, p.spec.serverIndex());
            if (ns == null || ns.gpus() != p.spec.gpus()) return false;
        }
        return true;
    }

    private static PartSpec matching(List<PartSpec> parts, int server) {
        PartSpec p = matchingOrNull(parts, server);
        if (p == null) throw new IllegalStateException("no part on server " + server);
        return p;
    }

    private static PartSpec matchingOrNull(List<PartSpec> parts, int server) {
        for (PartSpec p : parts) if (p.serverIndex() == server) return p;
        return null;
    }

    private void release(Part p) {
        host(p.spec.serverIndex()).release(p.spec);
    }

    private StubGpuHost host(int index) {
        if (index < 0 || index >= hosts.size()) throw new IllegalArgumentException("no server " + index);
        return hosts.get(index);
    }

    private JobRun requireRunning(int jobId) {
        JobRun r = jobs.get(jobId);
        if (r == null) throw new IllegalStateException("job " + jobId + " is not running");
        return r;
    }
}
