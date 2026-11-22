package org.cloudbus.cloudsim.cosched.workload;

import com.opencsv.CSVReaderHeaderAware;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadTest {

    static final Path REFERENCE = Path.of("../../presets/reference/synergy_ref_fifo_tune_9jph.csv");

    static List<JobRecord> csv(String text) {
        return JobCsvReader.read(new StringReader(text), "test.csv");
    }

    @Test
    void readsBothWorkStylesAndIgnoresExtraColumns() {
        List<JobRecord> jobs = csv("""
                job_id,arrival_s,gpus,model,iterations,iter_time_s,duration_s,jct_s
                1,0,2,res18,1000,0.64,,123
                2,30.5,1,,,,7200,
                """);
        assertEquals(2, jobs.size());
        assertEquals(1000, jobs.get(0).iterations());
        assertEquals(0.64, jobs.get(0).iterTimeS());
        assertEquals("res18", jobs.get(0).model());
        assertNull(jobs.get(1).model());
        assertEquals(7200, jobs.get(1).durationS());
        assertEquals(3, jobs.get(1).line());
    }

    @Test
    void errorsNameTheLine() {
        var e = assertThrows(IllegalArgumentException.class, () -> csv("""
                job_id,arrival_s,gpus,iterations
                1,0,1,100
                2,0,-1,100
                """));
        assertTrue(e.getMessage().contains("line 3") && e.getMessage().contains("gpus"), e.getMessage());
        var both = assertThrows(IllegalArgumentException.class, () -> csv("job_id,arrival_s,gpus,iterations,duration_s\n1,0,1,100,50\n"));
        assertTrue(both.getMessage().contains("exactly one"), both.getMessage());
        var col = assertThrows(IllegalArgumentException.class, () -> csv("job_id,gpus,iterations\n1,1,100\n"));
        assertTrue(col.getMessage().contains("arrival_s"), col.getMessage());
        var dup = assertThrows(IllegalArgumentException.class, () -> csv("job_id,arrival_s,gpus,iterations\n1,0,1,100\n1,5,1,100\n"));
        assertTrue(dup.getMessage().contains("duplicate"), dup.getMessage());
    }

    @Test
    void fromTraceNeedsAModelOnEveryJob() {
        List<JobRecord> jobs = csv("job_id,arrival_s,gpus,iterations,model\n1,0,1,100,gnmt\n2,1,1,100,\n");
        var e = assertThrows(IllegalArgumentException.class, () -> new TraceWorkload(jobs, new FromTraceLabeler()));
        assertTrue(e.getMessage().contains("job 2"), e.getMessage());
    }

    @Test
    void traceWorkloadIsInArrivalOrder() {
        List<JobRecord> jobs = csv("job_id,arrival_s,gpus,iterations,model\n5,10,1,1,a\n3,0,1,1,b\n4,10,1,1,c\n");
        assertEquals(List.of(3, 4, 5), new TraceWorkload(jobs, new FromTraceLabeler()).jobs().stream().map(JobRecord::jobId).toList());
    }

    /** Synergy's labels for its measured jobs 4000-4999 (FIFO+tune, split 20/70/10) are reproduced exactly. */
    @Test
    void classSplitReproducesSynergysLabelsJobByJob() throws Exception {
        Map<Integer, String> ref = new HashMap<>();
        try (CSVReaderHeaderAware r = new CSVReaderHeaderAware(new FileReader(REFERENCE.toFile()))) {
            Map<String, String> row;
            while ((row = r.readMap()) != null) ref.put(Integer.parseInt(row.get("job id")), row.get("model"));
        }
        assertEquals(1000, ref.size());
        ClassSplitLabeler l = ClassSplitLabeler.synergy(20, 70, 10);
        List<JobRecord> all = new ArrayList<>();
        for (int id = 0; id < 5000; id++) all.add(new JobRecord(id, id, 1, 1.0, null, null, null, null, null, id + 2));
        TraceWorkload w = new TraceWorkload(all, l);
        int match = 0;
        for (JobRecord j : w.jobs()) if (ref.containsKey(j.jobId()) && ref.get(j.jobId()).equals(j.model())) match++;
        assertEquals(1000, match);
        assertEquals(List.of(1000, 3500, 500), l.counts());
    }

    @Test
    void classSplitStartsWithTheFirstClassAndRoundRobinsWithin() {
        ClassSplitLabeler l = ClassSplitLabeler.synergy(34, 33, 33);
        JobRecord any = new JobRecord(0, 0, 1, 1.0, null, null, null, null, null, 2);
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 6; i++) first.add(l.label(any));
        assertEquals(List.of("alexnet", "gnmt", "m5", "res18", "transformer", "deepspeech"), first);
        assertThrows(IllegalArgumentException.class, () -> ClassSplitLabeler.synergy(50, 30, 10));
    }
}
