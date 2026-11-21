import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.provisioners.*;
import java.util.*;

enum DTag implements CloudSimTags { CREATE, CHECK, END }

// puts each VM exactly on the host the plan names, and never anywhere else
class PlannedPolicy extends VmAllocationPolicySimple {
    static final Map<Integer, Integer> plan = new HashMap<>();   // vmId -> host index
    PlannedPolicy(List<? extends HostEntity> l) { super(l); }
    @Override public HostEntity findHostForGuest(GuestEntity g) {
        Integer idx = plan.get(g.getId());
        if (idx == null) return null;
        List<HostEntity> hl = getHostList();
        HostEntity h = hl.get(idx);
        return h.isSuitableForGuest(g) ? h : null;               // full -> refuse, no fallback
    }
}

class PlaceBroker extends DatacenterBroker {
    int dc = -1;
    final List<Host> hosts;
    final Map<Integer, Vm> vms = new HashMap<>();
    final Map<Integer, Cloudlet> jobs = new HashMap<>();
    final Set<Integer> failed = new TreeSet<>();

    PlaceBroker(String name, List<Host> hosts) throws Exception { super(name); this.hosts = hosts; }

    @Override protected void processResourceCharacteristics(SimEvent ev) {
        dc = ((DatacenterCharacteristics) ev.getData()).getId();
        for (int i = 0; i < 20; i++) schedule(getId(), 0, DTag.CREATE, i);
        schedule(getId(), 10, DTag.CREATE, 20);      // host 0 is full by now -> must fail
        schedule(getId(), 100, DTag.CREATE, 21);     // VM 0 has finished -> must succeed
        schedule(getId(), 5, DTag.CHECK, 1);
        schedule(getId(), 105, DTag.CHECK, 2);
        schedule(getId(), 2000, DTag.END, 0);
    }

    @Override protected void processOtherEvent(SimEvent ev) {
        if (ev.getTag() == DTag.CREATE) create((Integer) ev.getData());
        else if (ev.getTag() == DTag.CHECK) check((Integer) ev.getData());
        else finishExecution();
    }

    void create(int i) {
        Vm vm = new Vm(i, getId(), 1000, 1, 512, 1000, 1000, "Xen", new CloudletSchedulerTimeShared());
        UtilizationModel um = new UtilizationModelFull();
        long length = (i == 0 || i == 21) ? 50_000L : 1_000_000L;   // VM 0 and 21 are short jobs
        Cloudlet c = new Cloudlet(i, length, 1, 300, 300, um, um, um);
        c.setUserId(getId()); c.setGuestId(i);
        vms.put(i, vm); jobs.put(i, c);
        sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);
    }

    @Override protected void processVmCreateAck(SimEvent ev) {
        int[] d = (int[]) ev.getData();
        if (d[2] == CloudSimTags.TRUE) sendNow(dc, CloudActionTags.CLOUDLET_SUBMIT, jobs.get(d[1]));
        else failed.add(d[1]);
    }

    @Override protected void processCloudletReturn(SimEvent ev) {
        Cloudlet c = (Cloudlet) ev.getData();
        sendNow(dc, CloudActionTags.VM_DESTROY, vms.get(c.getCloudletId()));
    }

    int hostIndex(Vm vm) {
        HostEntity h = vm.getHost();
        return h == null ? -1 : hosts.indexOf(h);
    }

    void check(int which) {
        if (which == 1) {
            int mismatch = 0; int[] perHost = new int[hosts.size()];
            for (int i = 0; i < 20; i++) {
                int actual = hostIndex(vms.get(i));
                if (actual != PlannedPolicy.plan.get(i)) mismatch++;
                if (actual >= 0) perHost[actual]++;
            }
            System.out.println("t=5   placement mismatches : " + mismatch + " of 20");
            System.out.println("t=5   VMs per host         : " + Arrays.toString(perHost) + "  (plan: [8, 8, 4, 0])");
        } else {
            System.out.println("t=105 VM 20 (host was full): created=" + !failed.contains(20) + ", host=" + hostIndex(vms.get(20)));
            System.out.println("t=105 VM 21 (slot freed)   : created=" + !failed.contains(21) + ", host=" + hostIndex(vms.get(21)) + "  (plan: 0)");
        }
    }
}

public class CheckD {
    public static void main(String[] a) throws Exception {
        boolean control = a.length > 0 && a[0].equals("default");
        for (int i = 0; i < 8; i++)  PlannedPolicy.plan.put(i, 0);
        for (int i = 8; i < 16; i++) PlannedPolicy.plan.put(i, 1);
        for (int i = 16; i < 20; i++) PlannedPolicy.plan.put(i, 2);
        PlannedPolicy.plan.put(20, 0);
        PlannedPolicy.plan.put(21, 0);

        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < 4; h++) {
            List<Pe> pes = new ArrayList<>();
            for (int p = 0; p < 8; p++) pes.add(new Pe(new PeProvisionerSimple(1000)));
            hosts.add(new Host(new RamProvisionerSimple(500000), new BwProvisionerSimple(1000000),
                    10000000L, pes, new VmSchedulerTimeShared(pes)));
        }
        DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen",
                hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        VmAllocationPolicy policy = control ? new VmAllocationPolicySimple(hosts) : new PlannedPolicy(hosts);
        new Datacenter("DC", ch, policy, new LinkedList<Storage>(), 0);
        PlaceBroker b = new PlaceBroker("Broker", hosts);

        System.out.println(control ? "== CONTROL: CloudSim default policy ==" : "== Planned policy ==");
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        System.out.println("failed VM creations: " + b.failed);
    }
}