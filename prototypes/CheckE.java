import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.provisioners.*;
import java.util.*;

enum ETag implements CloudSimTags { ARRIVE, ROUND }

class PlannedPolicy extends VmAllocationPolicySimple {
    static final Map<Integer, Integer> plan = new HashMap<>();   // vmId -> host index
    PlannedPolicy(List<? extends HostEntity> l) { super(l); }
    @Override public HostEntity findHostForGuest(GuestEntity g) {
        Integer idx = plan.get(g.getId());
        if (idx == null) return null;
        List<HostEntity> hl = getHostList();
        HostEntity h = hl.get(idx);
        return h.isSuitableForGuest(g) ? h : null;
    }
}

class SplitBroker extends DatacenterBroker {
    static final double ROUND = 300, GAP = 7;
    final int n; final int[] k; final long[] len; final double[][] speeds;
    final double loc; final List<Host> hosts;
    int dc = -1, jobsDone = 0, resizes = 0, resizeFails = 0, createFails = 0, placeMismatch = 0;
    double maxErr = 0, maxSpread = 0;
    final int[] partsLeft; final double[] jMin, jMax;
    final Map<Integer, Vm> active = new HashMap<>();   // vmId = job*10 + part
    final Map<Integer, Cloudlet> jobs = new HashMap<>();

    SplitBroker(String name, int n, int[] k, long[] len, double[][] speeds, double loc, List<Host> hosts) throws Exception {
        super(name); this.n = n; this.k = k; this.len = len; this.speeds = speeds; this.loc = loc; this.hosts = hosts;
        partsLeft = new int[n]; jMin = new double[n]; jMax = new double[n];
        Arrays.fill(jMin, Double.MAX_VALUE);
    }

    double L(int job) { return k[job] > 1 ? loc : 1.0; }     // locality factor

    @Override protected void processResourceCharacteristics(SimEvent ev) {
        dc = ((DatacenterCharacteristics) ev.getData()).getId();
        schedule(getId(), 0, ETag.ARRIVE, 0);
        schedule(getId(), ROUND, ETag.ROUND, 1);
    }

    @Override protected void processOtherEvent(SimEvent ev) {
        if (ev.getTag() == ETag.ARRIVE) arrive((Integer) ev.getData());
        else round((Integer) ev.getData());
    }

    void arrive(int i) {
        partsLeft[i] = k[i];
        for (int p = 0; p < k[i]; p++) {
            int id = i * 10 + p;
            PlannedPolicy.plan.put(id, (i * 3 + p) % hosts.size());     // parts on different servers
            Vm vm = new Vm(id, getId(), 1000 * L(i), 1, 512, 1000, 1000, "Xen", new CloudletSchedulerTimeShared());
            UtilizationModel um = new UtilizationModelFull();
            Cloudlet c = new Cloudlet(id, len[i], 1, 300, 300, um, um, um);
            c.setUserId(getId()); c.setGuestId(id);
            active.put(id, vm); jobs.put(id, c);
            sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);
        }
        if (i + 1 < n) schedule(getId(), GAP, ETag.ARRIVE, i + 1);
    }

    @Override protected void processVmCreateAck(SimEvent ev) {
        int[] d = (int[]) ev.getData();
        int id = d[1];
        if (d[2] == CloudSimTags.TRUE) {
            HostEntity h = active.get(id).getHost();
            if (hosts.indexOf(h) != PlannedPolicy.plan.get(id)) placeMismatch++;
            sendNow(dc, CloudActionTags.CLOUDLET_SUBMIT, jobs.get(id));
        } else createFails++;
    }

    void round(int r) {
        if (jobsDone == n || r >= 399) { finishExecution(); return; }
        double now = CloudSim.clock();
        List<Vm> running = new ArrayList<>();
        for (Vm v : active.values()) if (v.getHost() != null) running.add(v);
        for (Vm v : running) v.getHost().updateCloudletsProcessing(now);          // credit old speed
        for (Vm v : running) {                                                    // resize every part
            HostEntity h = v.getHost();
            h.getGuestScheduler().deallocatePesForGuest(v);
            double s = speeds[v.getId() / 10][r] * L(v.getId() / 10);
            boolean ok = h.getGuestScheduler().allocatePesForGuest(v, Arrays.asList(s));
            resizes++; if (!ok) resizeFails++;
        }
        sendNow(dc, CloudActionTags.VM_DATACENTER_EVENT);
        schedule(getId(), ROUND, ETag.ROUND, r + 1);
    }

    double expected(int job, double start) {                // independent calculation
        double t = start, rem = len[job], speed = 1000 * L(job);
        int r = (int) Math.floor(t / ROUND) + 1;
        while (true) {
            double b = ROUND * r, can = speed * (b - t);
            if (rem <= can) return t + rem / speed;
            rem -= can; t = b; speed = speeds[job][r] * L(job); r++;
        }
    }

    @Override protected void processCloudletReturn(SimEvent ev) {
        Cloudlet c = (Cloudlet) ev.getData();
        int id = c.getCloudletId(), job = id / 10;
        double fin = c.getExecFinishTime();
        maxErr = Math.max(maxErr, Math.abs(fin - expected(job, c.getExecStartTime())));
        jMin[job] = Math.min(jMin[job], fin); jMax[job] = Math.max(jMax[job], fin);
        sendNow(dc, CloudActionTags.VM_DESTROY, active.remove(id));
        jobs.remove(id);
        if (--partsLeft[job] == 0) { jobsDone++; maxSpread = Math.max(maxSpread, jMax[job] - jMin[job]); }
    }
}

public class CheckE {
    public static void main(String[] a) throws Exception {
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 50;
        double loc = a.length > 1 ? Double.parseDouble(a[1]) : 0.9;
        Random rnd = new Random(42);
        int[] kChoices = {1, 2, 4};
        int[] k = new int[n]; long[] len = new long[n]; int split = 0;
        for (int i = 0; i < n; i++) { k[i] = kChoices[rnd.nextInt(3)]; len[i] = 100_000L + rnd.nextInt(1_900_000); if (k[i] > 1) split++; }
        double[] choices = {250, 500, 1000, 1500, 2000};
        double[][] speeds = new double[n][400];
        for (int i = 0; i < n; i++) for (int r = 0; r < 400; r++) speeds[i][r] = choices[rnd.nextInt(5)];

        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < 8; h++) {
            List<Pe> pes = new ArrayList<>();
            pes.add(new Pe(new PeProvisionerSimple(100000)));
            hosts.add(new Host(new RamProvisionerSimple(500000), new BwProvisionerSimple(1000000),
                    10000000L, pes, new VmSchedulerTimeShared(pes)));
        }
        DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen",
                hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        new Datacenter("DC", ch, new PlannedPolicy(hosts), new LinkedList<Storage>(), 0);
        SplitBroker b = new SplitBroker("Broker", n, k, len, speeds, loc, hosts);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        System.out.println("jobs finished        : " + b.jobsDone + " / " + n + "  (split over servers: " + split + ")");
        System.out.println("locality factor      : " + loc);
        System.out.println("VM creations failed  : " + b.createFails);
        System.out.println("placement mismatches : " + b.placeMismatch);
        System.out.println("resize ops (failed)  : " + b.resizes + " (" + b.resizeFails + ")");
        System.out.println("max finish err       : " + b.maxErr + " s");
        System.out.println("max part spread      : " + b.maxSpread + " s (gap between a job's first and last part)");
    }
}