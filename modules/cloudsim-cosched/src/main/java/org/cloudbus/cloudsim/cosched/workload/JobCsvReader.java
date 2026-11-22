package org.cloudbus.cloudsim.cosched.workload;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the job CSV. Required columns: {@code job_id, arrival_s, gpus}, plus {@code iterations}
 * (with optional {@code iter_time_s}) or {@code duration_s}. Optional: {@code model, team, priority}.
 * Other columns (for example {@code jct_s} in a reference file) are ignored. Every error names its line.
 */
public final class JobCsvReader {

    private JobCsvReader() {
    }

    public static List<JobRecord> read(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            return read(r, file.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read job CSV " + file + ": " + e.getMessage(), e);
        }
    }

    public static List<JobRecord> read(Reader in, String name) {
        List<String[]> rows;
        try (CSVReader csv = new CSVReader(in)) {
            rows = csv.readAll();
        } catch (IOException | CsvException e) {
            throw new IllegalArgumentException("cannot parse job CSV " + name + ": " + e.getMessage(), e);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("job CSV " + name + " is empty");
        Map<String, Integer> col = new HashMap<>();
        String[] header = rows.get(0);
        for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);
        for (String req : List.of("job_id", "arrival_s", "gpus"))
            if (!col.containsKey(req)) throw new IllegalArgumentException("job CSV " + name + ": missing column '" + req + "'; found " + col.keySet());
        if (!col.containsKey("iterations") && !col.containsKey("duration_s"))
            throw new IllegalArgumentException("job CSV " + name + ": needs an 'iterations' or a 'duration_s' column");

        List<JobRecord> out = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            int line = r + 1;
            if (row.length == 1 && row[0].isBlank()) continue;
            try {
                JobRecord rec = new JobRecord(
                        (int) parse(row, col, "job_id", true),
                        parse(row, col, "arrival_s", true),
                        (int) parse(row, col, "gpus", true),
                        opt(row, col, "iterations"), opt(row, col, "iter_time_s"), opt(row, col, "duration_s"),
                        text(row, col, "model"), text(row, col, "team"), text(row, col, "priority"), line);
                if (!ids.add(rec.jobId())) throw new IllegalArgumentException("duplicate job_id " + rec.jobId());
                out.add(rec);
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage();
                throw new IllegalArgumentException(msg.startsWith("job CSV line") ? name + ": " + msg : name + " line " + line + ": " + msg, e);
            }
        }
        return out;
    }

    private static double parse(String[] row, Map<String, Integer> col, String c, boolean required) {
        String v = cell(row, col, c);
        if (v == null) {
            if (required) throw new IllegalArgumentException("missing value for '" + c + "'");
            return Double.NaN;
        }
        try {
            double d = Double.parseDouble(v);
            if ((c.equals("job_id") || c.equals("gpus")) && d != Math.rint(d)) throw new IllegalArgumentException("'" + c + "' must be a whole number, got '" + v + "'");
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + c + "' is not a number: '" + v + "'");
        }
    }

    private static Double opt(String[] row, Map<String, Integer> col, String c) {
        return cell(row, col, c) == null ? null : parse(row, col, c, true);
    }

    private static String text(String[] row, Map<String, Integer> col, String c) {
        return cell(row, col, c);
    }

    private static String cell(String[] row, Map<String, Integer> col, String c) {
        Integer i = col.get(c);
        if (i == null || i >= row.length) return null;
        String v = row[i].trim();
        return v.isEmpty() ? null : v;
    }
}
