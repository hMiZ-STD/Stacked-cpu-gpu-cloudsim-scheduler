import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.provisioners.*;
import java.util.*;

enum CTag implements CloudSimTags { ARRIVE, ROUND }

class RoundBroker extends DatacenterBroker {
    static final double ROUND = 300, ARRIVAL_GAP = 7;
    final int n; final long[] len; final double[][] speeds;
    int dc = -1, done = 0, resizes = 0, resizeFails = 0;
    double maxErr = 0, sumErr = 0, lastFinish = 0; int worst = -1;
    final Map<Integer, Vm> active = new HashMap<>();
    final Map<Integer, Cloudlet> jobs = new HashMap<>();

    RoundBroker(String name, int n, long[] len, double[][] speeds) throws Exception {
        super(name); this.n = n; this.len = len; this.speeds = speeds;
    }

    @Override protected void processResourceCharacteristics(SimEvent ev) {
        dc = ((DatacenterCharacteristics) ev.getData()).getId();
        schedule(getId(), 0, CTag.ARRIVE, 0);
        schedule(getId(), ROUND, CTag.ROUND, 1);
    }

    @Override protected void processOtherEvent(SimEvent ev) {
        if (ev.getTag() == CTag.ARRIVE) arrive((Integer) ev.getData());
        else round((Integer) ev.getData());
    }

    void arrive(int i) {
        Vm vm = new Vm(i, getId(), 1000, 1, 512, 1000, 1000, "Xen", new CloudletSchedulerTimeShared());
        UtilizationModel um = new UtilizationModelFull();
        Cloudlet c = new Cloudlet(i, len[i], 1, 300, 300, um, um, um);
        c.setUserId(getId()); c.setGuestId(i);
        active.put(i, vm); jobs.put(i, c);
        sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);
        if (i + 1 < n) schedule(getId(), ARRIVAL_GAP, CTag.ARRIVE, i + 1);
    }

    void round(int r) {
        if(done == n){
            finishExecution();
            return;
        }
        double now = CloudSim.clock();
        List<Vm> running = new ArrayList<>();
        for (Vm v : active.values()) if (v.getHost() != null) running.add(v);
        // 1. credit progress at the old speeds
        for (Vm v : running) v.getHost().updateCloudletsProcessing(now);
        // 2. resize every running job
        for (Vm v : running) {
            HostEntity h = v.getHost();
            h.getGuestScheduler().deallocatePesForGuest(v);
            boolean ok = h.getGuestScheduler().allocatePesForGuest(v, Arrays.asList(speeds[v.getId()][r]));
            resizes++; if (!ok) resizeFails++;
        }
        // 3. make the datacenter recompute finish times
        sendNow(dc, CloudActionTags.VM_DATACENTER_EVENT);
        if (done < n) schedule(getId(), ROUND, CTag.ROUND, r + 1);
    }

    @Override protected void processVmCreateAck(SimEvent ev) {
        int[] d = (int[]) ev.getData();
        sendNow(dc, CloudActionTags.CLOUDLET_SUBMIT, jobs.get(d[1]));
    }

    // independent calculation of when a job should finish
    double expected(int id, double start) {
        double t = start, rem = len[id], speed = 1000;
        int r = (int) Math.floor(t / ROUND) + 1;
        while (true) {
            double b = ROUND * r, can = speed * (b - t);
            if (rem <= can) return t + rem / speed;
            rem -= can; t = b; speed = speeds[id][r]; r++;
        }
    }

    @Override protected void processCloudletReturn(SimEvent ev) {
        Cloudlet c = (Cloudlet) ev.getData();
        int id = c.getCloudletId();
        double err = Math.abs(c.getExecFinishTime() - expected(id, c.getExecStartTime()));
        sumErr += err; if (err > maxErr) { maxErr = err; worst = id; }
        lastFinish = c.getExecFinishTime();
        sendNow(dc, CloudActionTags.VM_DESTROY, active.remove(id));
        jobs.remove(id);
        done++;
    }
}

public class CheckC {
    public static void main(String[] a) throws Exception {
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 50;
        Random rnd = new Random(42);
        long[] len = new long[n];
        for (int i = 0; i < n; i++) len[i] = 100_000L + rnd.nextInt(1_900_000);
        double[] choices = {250, 500, 1000, 1500, 2000};
        double[][] speeds = new double[n][400];
        for (int i = 0; i < n; i++) for (int r = 0; r < 400; r++) speeds[i][r] = choices[rnd.nextInt(5)];

        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < 16; h++) {
            List<Pe> pes = new ArrayList<>();
            pes.add(new Pe(new PeProvisionerSimple(100000)));
            hosts.add(new Host(new RamProvisionerSimple(500000), new BwProvisionerSimple(1000000),
                    10000000L, pes, new VmSchedulerTimeShared(pes)));
        }
        DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen",
                hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        new Datacenter("DC", ch, new VmAllocationPolicySimple(hosts), new LinkedList<Storage>(), 0);
        RoundBroker b = new RoundBroker("Broker", n, len, speeds);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        System.out.println("jobs finished   : " + b.done + " / " + n);
        System.out.println("resize ops      : " + b.resizes + " (failed: " + b.resizeFails + ")");
        System.out.println("last finish (s) : " + b.lastFinish);
        System.out.println("max finish err  : " + b.maxErr + " s (job " + b.worst + ")");
        System.out.println("mean finish err : " + (b.sumErr / b.done) + " s");
    }
}
