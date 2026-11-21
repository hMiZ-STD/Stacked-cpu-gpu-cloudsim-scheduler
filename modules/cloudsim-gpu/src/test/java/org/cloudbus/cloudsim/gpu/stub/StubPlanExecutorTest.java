package org.cloudbus.cloudsim.gpu.stub;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.gpu.api.JobCommand;
import org.cloudbus.cloudsim.gpu.api.JobCommand.Resize;
import org.cloudbus.cloudsim.gpu.api.JobCommand.Start;
import org.cloudbus.cloudsim.gpu.api.JobCommand.Stop;
import org.cloudbus.cloudsim.gpu.api.PartSpec;
import org.cloudbus.cloudsim.gpu.api.PlanExecutor;
import org.cloudbus.cloudsim.gpu.api.ServerShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StubPlanExecutorTest {

    static final double TOL = 0.05;   // seconds; Check 1 measured worst 0.037 s over 16,000 s

    enum Tag implements CloudSimTags { STEP }

    /** Runs scripted actions at given times and records job ends. */
    static final class Driver extends SimEntity {
        final TreeMap<Double, List<Consumer<PlanExecutor>>> script = new TreeMap<>();
        final Map<Integer, Double> finished = new HashMap<>();
        final List<Integer> endOrder = new ArrayList<>();
        final StubGpuLayer layer;
        PlanExecutor exec;
        RuntimeException failure;

        Driver(StubGpuLayer layer) {
            super("driver");
            this.layer = layer;
        }

        Driver at(double t, Consumer<PlanExecutor> action) {
            script.computeIfAbsent(t, k -> new ArrayList<>()).add(action);
            return this;
        }

        Driver apply(double t, JobCommand... cmds) {
            return at(t, e -> e.apply(List.of(cmds)));
        }

        @Override
        public void startEntity() {
            exec = layer.bind(this);
            exec.setJobEndListener((id, time) -> { finished.put(id, time); endOrder.add(id); });
            for (double t : script.keySet()) schedule(getId(), t, Tag.STEP, t);
        }

        @Override
        public void processEvent(SimEvent ev) {
            try {
                if (exec.handle(ev)) return;
                if (ev.getTag() == Tag.STEP) for (var a : script.get((Double) ev.getData())) a.accept(exec);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
            }
        }

        @Override
        public void shutdownEntity() {
        }
    }

    static StubGpuLayer cluster(int servers) {
        return cluster(servers, 8);
    }

    static StubGpuLayer cluster(int servers, int gpusPerServer) {
        Log.setDisabled(true);
        CloudSim.init(1, Calendar.getInstance(), false);
        List<ServerShape> shapes = new ArrayList<>();
        for (int i = 0; i < servers; i++) shapes.add(new ServerShape(i, "g", gpusPerServer, "V100", 24, 500, 500, 40));
        StubGpuLayer layer = new StubGpuLayer();
        layer.build(shapes);
        return layer;
    }

    static List<PartSpec> on(int server, int gpus, double cpus) {
        return List.of(new PartSpec(server, gpus, cpus, 10 * gpus));
    }

    static Driver run(Driver d) {
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        if (d.failure != null) throw d.failure;
        return d;
    }

    @Test
    void constantRateFinishesAtWorkOverRate() {
        StubGpuLayer layer = cluster(1);
        Driver d = run(new Driver(layer).apply(0, new Start(1, 1000, on(0, 2, 6), 2.0)));
        assertEquals(500, d.finished.get(1), TOL);
        assertEquals(0, layer.hosts().get(0).usedGpus());
        assertEquals(0, layer.hosts().get(0).usedCpus(), 1e-9);
    }

    @Test
    void inPlaceResizeCreditsOldSpeedFirst() {
        StubGpuLayer layer = cluster(1);
        double[] remAt100 = new double[1];
        double[] cpusAfter = new double[1];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 2, 6), 1.0))
                .at(100, e -> remAt100[0] = e.remainingWork(1))
                .apply(100, new Resize(1, on(0, 2, 18), 4.0))
                .at(100, e -> cpusAfter[0] = layer.hosts().get(0).usedCpus());
        run(d);
        assertEquals(900, remAt100[0], 1e-3);
        assertEquals(18, cpusAfter[0], 1e-9);
        assertEquals(100 + 900 / 4.0, d.finished.get(1), TOL);
    }

    @Test
    void stopKeepsRemainingWorkAndRestartResumes() {
        StubGpuLayer layer = cluster(2);
        double[] rem = new double[1];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 4, 12), 1.0))
                .apply(100, new Stop(1))
                .at(150, e -> rem[0] = e.remainingWork(1))
                .at(200, e -> e.apply(List.of(new Start(1, e.remainingWork(1), on(1, 4, 12), 3.0))));
        run(d);
        assertEquals(900, rem[0], 1e-3);
        assertEquals(200 + 300, d.finished.get(1), TOL);
    }

    @Test
    void resizeToAnotherServerMovesTheJob() {
        StubGpuLayer layer = cluster(2);
        int[] gpusOnOldServer = new int[1];
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 2, 6), 2.0))
                .apply(100, new Resize(1, on(1, 2, 6), 1.0))
                .at(100, e -> gpusOnOldServer[0] = layer.hosts().get(0).usedGpus());
        run(d);
        assertEquals(0, gpusOnOldServer[0]);
        assertEquals(100 + 800 / 1.0, d.finished.get(1), TOL);
    }

    @Test
    void stopsInARoundFreeCapacityForStartsInTheSameRound() {
        StubGpuLayer layer = cluster(1);
        Driver d = new Driver(layer)
                .apply(0, new Start(1, 1000, on(0, 8, 24), 1.0))
                .apply(100, new Start(2, 50, on(0, 8, 24), 1.0), new Stop(1));   // order in list must not matter
        run(d);
        assertEquals(150, d.finished.get(2), TOL);
        assertFalse(d.finished.containsKey(1));
    }

    @Test
    void overCommitIsABug() {
        StubGpuLayer layer = cluster(1);
        Driver d = new Driver(layer).apply(0,
                new Start(1, 100, on(0, 8, 12), 1.0),
                new Start(2, 100, on(0, 1, 1), 1.0));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(d));
        assertTrue(e.getMessage().contains("over-committed"), e.getMessage());
    }

    @Test
    void spreadJobPartsFinishTogetherAndEndOnce() {
        StubGpuLayer layer = cluster(2);
        List<PartSpec> parts = List.of(new PartSpec(0, 4, 12, 40), new PartSpec(1, 4, 12, 40));
        Driver d = run(new Driver(layer).apply(0, new Start(7, 1000, parts, 2.0)));
        assertEquals(500, d.finished.get(7), TOL);
        assertEquals(List.of(7), d.endOrder);
    }

    /** CheckE ported: many jobs, 1-4 parts, random rate every 300 s round, exact finish times. */
    @Test
    void randomResizesKeepFinishTimesExact() {
        int n = 300, servers = 8;
        double round = 300;
        Random rnd = new Random(42);
        StubGpuLayer layer = cluster(servers, 1024);   // tests timing only; up to ~90 parts share a server
        Driver d = new Driver(layer);
        int[] k = new int[n];
        double[] work = new double[n], start = new double[n];
        double[][] rate = new double[n][200];
        double[] choices = {0.25, 0.5, 1, 1.5, 2};
        for (int j = 0; j < n; j++) {
            k[j] = new int[]{1, 2, 4}[rnd.nextInt(3)];
            work[j] = 100 + rnd.nextInt(1900);
            start[j] = j * 7.0;
            for (int r = 0; r < 200; r++) rate[j][r] = choices[rnd.nextInt(choices.length)];
            List<PartSpec> parts = new ArrayList<>();
            for (int p = 0; p < k[j]; p++) parts.add(new PartSpec((j * 3 + p) % servers, 1, 0.01, 0.01));
            final int job = j;
            d.at(start[j], e -> e.apply(List.of(new Start(job, work[job], parts, rate[job][0]))));
        }
        for (int r = 1; r < 200; r++) {
            final int rr = r;
            d.at(r * round, e -> {
                List<JobCommand> cmds = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    if (d.finished.containsKey(j) || start[j] > rr * round) continue;
                    List<PartSpec> parts = new ArrayList<>();
                    for (int p = 0; p < k[j]; p++) parts.add(new PartSpec((j * 3 + p) % servers, 1, 0.01, 0.01));
                    cmds.add(new Resize(j, parts, rate[j][rr]));
                }
                e.apply(cmds);
            });
        }
        run(d);
        assertEquals(n, d.finished.size());
        double maxErr = 0;
        for (int j = 0; j < n; j++) {
            double t = start[j], rem = work[j];
            int r = (int) Math.floor(t / round) + 1;
            double speed = rate[j][0];
            while (true) {
                double b = round * r, can = speed * (b - t);
                if (rem <= can) { t += rem / speed; break; }
                rem -= can; t = b; speed = rate[j][r]; r++;
            }
            maxErr = Math.max(maxErr, Math.abs(d.finished.get(j) - t));
        }
        assertTrue(maxErr < TOL, "worst finish error " + maxErr + " s");
        for (StubGpuHost h : layer.hosts()) assertEquals(0, h.usedGpus());
    }
}
