import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.provisioners.*;
import java.util.*;

enum ResizeTag implements CloudSimTags { RESIZE }

class ResizeBroker extends DatacenterBroker {
    int dc = -1;
    Vm vm; Cloudlet job;
    double finish = -1;
    // {time, new MIPS}
    final double[][] plan = { {200, 2000}, {300, 500} };

    ResizeBroker(String name) throws Exception { super(name); }

    @Override protected void processResourceCharacteristics(SimEvent ev) {
        dc = ((DatacenterCharacteristics) ev.getData()).getId();
        vm = new Vm(0, getId(), 1000, 1, 512, 1000, 1000, "Xen", new CloudletSchedulerTimeShared());
        UtilizationModel um = new UtilizationModelFull();
        job = new Cloudlet(0, 1_000_000L, 1, 300, 300, um, um, um);
        job.setUserId(getId()); job.setGuestId(0);
        sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);
        for (int i = 0; i < plan.length; i++) schedule(getId(), plan[i][0], ResizeTag.RESIZE, i);
    }

    @Override protected void processVmCreateAck(SimEvent ev) {
        sendNow(dc, CloudActionTags.CLOUDLET_SUBMIT, job);
    }

    @Override protected void processOtherEvent(SimEvent ev) {      // a resize
        double newMips = plan[(Integer) ev.getData()][1];
        HostEntity host = vm.getHost();
        // 1. credit all progress so far at the OLD speed
        host.updateCloudletsProcessing(CloudSim.clock());
        // 2. free the old share, then grant the new one
        host.getGuestScheduler().deallocatePesForGuest(vm);
        boolean ok = host.getGuestScheduler().allocatePesForGuest(vm, Arrays.asList(newMips));
        System.out.println("t=" + CloudSim.clock() + " resize to " + newMips + " ok=" + ok
            + " allocated=" + host.getGuestScheduler().getAllocatedMipsForGuest(vm)
            + " hostFree=" + host.getGuestScheduler().getAvailableMips());
        // 3. make the datacenter recompute when the job will finish
        sendNow(dc, CloudActionTags.VM_DATACENTER_EVENT);
    }

    @Override protected void processCloudletReturn(SimEvent ev) {
        Cloudlet c = (Cloudlet) ev.getData();
        finish = c.getExecFinishTime();
        sendNow(dc, CloudActionTags.VM_DESTROY, vm);
        finishExecution();
    }
}

public class CheckB {
    public static void main(String[] a) throws Exception {
        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        List<Pe> pes = new ArrayList<>();
        pes.add(new Pe(new PeProvisionerSimple(10000)));      // one big PE, so 2000 MIPS fits
        List<Host> hosts = new ArrayList<>();
        hosts.add(new Host(new RamProvisionerSimple(500000), new BwProvisionerSimple(1000000),
                10000000L, pes, new VmSchedulerTimeShared(pes)));
        DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen",
                hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        new Datacenter("DC", ch, new VmAllocationPolicySimple(hosts), new LinkedList<Storage>(), 0);
        ResizeBroker b = new ResizeBroker("Broker");

        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        System.out.println("expected finish : 1500.02");
        System.out.println("actual finish   : " + b.finish);
    }
}