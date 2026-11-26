package org.cloudbus.cloudsim.cosched.workload;

import org.cloudbus.cloudsim.cosched.StandardPlugins;
import org.cloudbus.cloudsim.cosched.core.Params;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PoissonWorkloadTest {

    static final Path BASE = Path.of(".");

    @Test
    void sameSeedSameJobsOtherSeedOtherJobs() {
        PoissonWorkload g = PoissonWorkload.synthetic(9, 500, PoissonWorkload.GpuMix.SYNERGY_MIX);
        assertEquals(g.generate(7, BASE), g.generate(7, BASE));
        assertNotEquals(g.generate(7, BASE), g.generate(8, BASE));
    }

    @Test
    void arrivalsArePoissonAtTheGivenLoadInWholeSeconds() {
        List<JobRecord> jobs = PoissonWorkload.synthetic(9, 20_000, PoissonWorkload.GpuMix.SINGLE).generate(1, BASE);
        double last = jobs.get(jobs.size() - 1).arrivalS();
        assertEquals(3600 / 9.0, last / jobs.size(), 3600 / 9.0 * 0.03);   // mean inter-arrival 400 s
        double prev = 0;
        for (JobRecord j : jobs) {
            assertEquals(Math.rint(j.arrivalS()), j.arrivalS(), "whole seconds, as Synergy");
            assertTrue(j.arrivalS() >= prev + 1, "ceil makes every gap at least 1 s");
            prev = j.arrivalS();
            assertEquals(1, j.gpus());
            assertNull(j.model(), "models come from the labeller");
        }
    }

    @Test
    void durationsFollowSynergysSyntheticRecipe() {
        List<JobRecord> jobs = PoissonWorkload.synthetic(9, 20_000, PoissonWorkload.GpuMix.SYNERGY_MIX).generate(2, BASE);
        long longJobs = jobs.stream().filter(j -> j.durationS() > 60 * 1000).count();
        assertEquals(0.2, longJobs / 20_000.0, 0.015);
        for (JobRecord j : jobs) {
            assertTrue(j.durationS() >= 60 * Math.pow(10, 1.5) - 1e-6 && j.durationS() <= 60 * 1e4 + 1e-6, "duration " + j.durationS());
            assertNull(j.iterations());
        }
        Map<Integer, Long> byGpus = jobs.stream().collect(Collectors.groupingBy(JobRecord::gpus, Collectors.counting()));
        assertEquals(Set.of(1, 2, 4, 8), byGpus.keySet());
        assertEquals(0.70, byGpus.get(1) / 20_000.0, 0.015);
        assertEquals(0.10, byGpus.get(2) / 20_000.0, 0.015);
        assertEquals(0.15, byGpus.get(4) / 20_000.0, 0.015);
        assertEquals(0.05, byGpus.get(8) / 20_000.0, 0.015);
    }

    @Test
    void resamplingCopiesRowsOfTheGivenTraceWithNewArrivals(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pool.csv"), """
                job_id,arrival_s,gpus,model,iterations
                1,5,2,res18,1000
                2,9,4,gnmt,3000
                """);
        List<JobRecord> jobs = PoissonWorkload.resample(3, 400, "pool.csv").generate(3, dir);
        Set<String> seen = jobs.stream().map(j -> j.gpus() + "/" + j.model() + "/" + j.iterations()).collect(Collectors.toSet());
        assertEquals(Set.of("2/res18/1000.0", "4/gnmt/3000.0"), seen);
        assertEquals(400, jobs.size());
        assertEquals(399, jobs.get(399).jobId(), "ids are renumbered from 0");
    }

    @Test
    void writtenWorkloadReadsBackIdentically(@TempDir Path dir) {
        List<JobRecord> jobs = PoissonWorkload.synthetic(5, 200, PoissonWorkload.GpuMix.SYNERGY_MIX).generate(4, BASE);
        List<JobRecord> labelled = new TraceWorkload(jobs, ClassSplitLabeler.synergy(20, 70, 10)).jobs();
        JobCsvWriter.write(dir.resolve("w.csv"), labelled);
        List<JobRecord> back = JobCsvReader.read(dir.resolve("w.csv"));
        assertEquals(labelled.size(), back.size());
        for (int i = 0; i < back.size(); i++) {
            JobRecord a = labelled.get(i), b = back.get(i);
            assertEquals(a.jobId(), b.jobId());
            assertEquals(a.arrivalS(), b.arrivalS());
            assertEquals(a.gpus(), b.gpus());
            assertEquals(a.durationS(), b.durationS(), "exact round trip");
            assertEquals(a.model(), b.model());
        }
    }

    @Test
    void loadAndJobCountMustBeWritten() {
        var reg = StandardPlugins.registry();
        var e = assertThrows(IllegalArgumentException.class,
                () -> reg.create(WorkloadGenerator.class, "poisson", new Params("gen", Map.of("jobs", 10))));
        assertTrue(e.getMessage().contains("jobs_per_hour") && e.getMessage().contains("required"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> reg.create(WorkloadGenerator.class, "poisson",
                new Params("gen", Map.of("jobs", 10, "jobs_per_hour", 2, "source", "resample"))));
        assertTrue(e.getMessage().contains("resample_csv"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> reg.create(WorkloadGenerator.class, "poisson",
                new Params("gen", Map.of("jobs", 10, "jobs_per_hour", 2, "source", "resample", "resample_csv", "x.csv", "gpus", "single"))));
        assertTrue(e.getMessage().contains("unknown parameter") && e.getMessage().contains("gpus"), "gpus does not apply to resampling: " + e.getMessage());
    }
}
