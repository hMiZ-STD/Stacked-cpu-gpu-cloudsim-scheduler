package org.cloudbus.cloudsim.cosched.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Everything needed to reproduce one run: inputs (with their SHA-256), the git commit, the
 * candidate's plug-ins with every resolved parameter, the seed, model settings, warnings and the
 * result summary. Written as {@code manifest.json} next to {@code jobs.csv}.
 */
public final class RunManifest {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public RunManifest put(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), fields);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Commit of the code that ran, with "+dirty" if there are uncommitted changes; "unknown" outside git. */
    public static String gitCommit(Path anyPathInRepo) {
        Path dir = anyPathInRepo.toAbsolutePath();
        if (!Files.isDirectory(dir)) dir = dir.getParent();
        try {
            String head = run(dir, "git", "rev-parse", "HEAD");
            if (head.isEmpty()) return "unknown";
            String status = run(dir, "git", "status", "--porcelain", "--untracked-files=no");
            return status.isEmpty() ? head : head + "+dirty";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return "";
        return new String(out, StandardCharsets.UTF_8).trim();
    }
}
