import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.provisioners.*;
import java.util.*;

enum JobTag implements CloudSimTags { JOB_ARRIVAL }

class JobBroker extends DatacenterBroker {
    final int n; final double gap; final long[] len;
    int dc = -1, done = 0, failed = 0;
    double maxErr = 0, lastFinish = 0; long peakMem = 0;
    final Map<Integer, Vm> vms = new HashMap<>();
    final Map<Integer, Cloudlet> jobs = new HashMap<>();

    JobBroker(String name, int n, double gap, long[] len) throws Exception {
        super(name); this.n = n; this.gap = gap; this.len = len;
    }

    @Override protected void processResourceCharacteristics(SimEvent ev) {
        dc = ((DatacenterCharacteristics) ev.getData()).getId();
        schedule(getId(), 0, JobTag.JOB_ARRIVAL, 0);          // first job arrives now
    }

    @Override protected void processOtherEvent(SimEvent ev) {   // a job arrives
        int i = (Integer) ev.getData();
        Vm vm = new Vm(i, getId(), 1000, 1, 512, 1000, 1000, "Xen", new CloudletSchedulerTimeShared());
        UtilizationModel um = new UtilizationModelFull();
        Cloudlet c = new Cloudlet(i, len[i], 1, 300, 300, um, um, um);
        c.setUserId(getId()); c.setGuestId(i);
        vms.put(i, vm); jobs.put(i, c);
        sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);          // create this job's VM
        if (i + 1 < n) schedule(getId(), gap, JobTag.JOB_ARRIVAL, i + 1);
    }

    @Override protected void processVmCreateAck(SimEvent ev) {  // VM exists -> run the job
        int[] d = (int[]) ev.getData();
        if (d[2] == CloudSimTags.TRUE) sendNow(dc, CloudActionTags.CLOUDLET_SUBMIT, jobs.get(d[1]));
        else { failed++; vms.remove(d[1]); jobs.remove(d[1]); finishOne(); }
    }

    @Override protected void processCloudletReturn(SimEvent ev) { // job done -> destroy VM
        Cloudlet c = (Cloudlet) ev.getData();
        double expected = c.getExecStartTime() + c.getCloudletLength() / 1000.0;
        maxErr = Math.max(maxErr, Math.abs(c.getExecFinishTime() - expected));
        lastFinish = c.getExecFinishTime();
        sendNow(dc, CloudActionTags.VM_DESTROY, vms.remove(c.getCloudletId()));
        jobs.remove(c.getCloudletId());
        finishOne();
    }

    void finishOne() {
        done++;
        if (done % 10000 == 0) {
            System.gc();
            Runtime r = Runtime.getRuntime();
            peakMem = Math.max(peakMem, (r.totalMemory() - r.freeMemory()) >> 20);
        }
        if (done == n) finishExecution();
    }
}

public class CheckA {
    public static void main(String[] a) throws Exception {
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 1000;
        double gap = 10;                                  // seconds between arrivals
        Random rnd = new Random(42);
        long[] len = new long[n];
        for (int i = 0; i < n; i++) len[i] = (100 + rnd.nextInt(900)) * 1000L; // 100-999 s at 1000 MIPS

        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);

        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < 16; h++) {                    // 16 servers x 24 CPUs
            List<Pe> pes = new ArrayList<>();
            for (int p = 0; p < 24; p++) pes.add(new Pe(new PeProvisionerSimple(1000)));
            hosts.add(new Host(new RamProvisionerSimple(500000), new BwProvisionerSimple(1000000),
                    10000000L, pes, new VmSchedulerTimeShared(pes)));
        }
        DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen",
                hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        Datacenter dcObj = new Datacenter("DC", ch, new VmAllocationPolicySimple(hosts),
                new LinkedList<Storage>(), 0);
        JobBroker b = new JobBroker("Broker", n, gap, len);

        long t0 = System.nanoTime();
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        double wall = (System.nanoTime() - t0) / 1e9;

        System.out.println("jobs requested      : " + n);
        System.out.println("jobs finished       : " + (b.done - b.failed));
        System.out.println("VM creations failed : " + b.failed);
        System.out.println("VMs left in DC      : " + dcObj.getVmList().size());
        System.out.println("last finish (sim s) : " + b.lastFinish);
        System.out.println("max finish-time err : " + b.maxErr + " s");
        System.out.println("peak heap (MB)      : " + b.peakMem);
        System.out.println("wall-clock (s)      : " + wall);
    }
}