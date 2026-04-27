package com.alibaba.arthas.idea.workbench.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import org.eclipse.jifa.jfr.model.AnalysisResult;
import org.eclipse.jifa.jfr.vo.FlameGraph;
import org.eclipse.jifa.jfr.vo.Metadata;

/**
 * 内嵌 JFR 分析使用独立的磁盘缓存，避免和 Jifa Web 的 worker/storage 目录混用。
 */
final class JfrAnalysisCache {

    private static final String PLUGIN_HOME_DIR = ".arthas-workbench-plugin";
    private static final String JFR_ROOT_DIR = "jfr";
    private static final String FILES_DIR = "files";
    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String FLAME_GRAPHS_DIR = "flame-graphs";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final JfrAnalysisCache DEFAULT =
            new JfrAnalysisCache(defaultRootDirectory(Path.of(System.getProperty("user.home"))));

    private final Path rootDirectory;

    JfrAnalysisCache(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
    }

    static JfrAnalysisCache defaultCache() {
        return DEFAULT;
    }

    static Path defaultRootDirectory(Path userHome) {
        return userHome.resolve(PLUGIN_HOME_DIR).resolve(JFR_ROOT_DIR);
    }

    Path rootDirectory() {
        return rootDirectory;
    }

    CachedSnapshot loadSnapshot(Path sourcePath) throws IOException {
        Path snapshotFile = snapshotFile(sourcePath);
        if (!Files.isRegularFile(snapshotFile)) {
            return null;
        }
        SnapshotDocument document;
        try {
            document = GSON.fromJson(Files.readString(snapshotFile, StandardCharsets.UTF_8), SnapshotDocument.class);
        } catch (RuntimeException exception) {
            deleteRecursively(fileDirectory(sourcePath));
            return null;
        }
        if (document == null || document.metadata == null || document.result == null) {
            return null;
        }
        String fingerprint = fingerprint(sourcePath);
        if (!Objects.equals(fingerprint, document.fingerprint)) {
            return null;
        }
        return new CachedSnapshot(document.fingerprint, document.metadata, document.result);
    }

    void storeSnapshot(Path sourcePath, Metadata metadata, AnalysisResult result) throws IOException {
        Path fileDirectory = fileDirectory(sourcePath);
        ensureDirectory(fileDirectory);
        String fingerprint = fingerprint(sourcePath);

        Path snapshotFile = fileDirectory.resolve(SNAPSHOT_FILE);
        SnapshotDocument current = null;
        if (Files.isRegularFile(snapshotFile)) {
            try {
                current = GSON.fromJson(Files.readString(snapshotFile, StandardCharsets.UTF_8), SnapshotDocument.class);
            } catch (RuntimeException ignored) {
                deleteRecursively(fileDirectory);
                ensureDirectory(fileDirectory);
            }
        }
        if (current != null && !Objects.equals(current.fingerprint, fingerprint)) {
            deleteRecursively(fileDirectory.resolve(FLAME_GRAPHS_DIR));
        }

        SnapshotDocument document = new SnapshotDocument();
        document.fingerprint = fingerprint;
        document.metadata = metadata;
        document.result = result;
        document.updatedAtEpochMillis = System.currentTimeMillis();
        Files.writeString(snapshotFile, GSON.toJson(document), StandardCharsets.UTF_8);
    }

    FlameGraph loadFlameGraph(Path sourcePath, String dimensionKey) throws IOException {
        CachedSnapshot snapshot = loadSnapshot(sourcePath);
        if (snapshot == null) {
            return null;
        }
        Path flameGraphFile = flameGraphFile(sourcePath, dimensionKey);
        if (!Files.isRegularFile(flameGraphFile)) {
            return null;
        }
        FlameGraphDocument document;
        try {
            document =
                    GSON.fromJson(Files.readString(flameGraphFile, StandardCharsets.UTF_8), FlameGraphDocument.class);
        } catch (RuntimeException exception) {
            Files.deleteIfExists(flameGraphFile);
            return null;
        }
        if (document == null || document.flameGraph == null) {
            return null;
        }
        return Objects.equals(document.fingerprint, snapshot.fingerprint) ? document.flameGraph : null;
    }

    void storeFlameGraph(Path sourcePath, String dimensionKey, FlameGraph flameGraph) throws IOException {
        Path flameGraphFile = flameGraphFile(sourcePath, dimensionKey);
        ensureDirectory(flameGraphFile.getParent());
        FlameGraphDocument document = new FlameGraphDocument();
        document.fingerprint = fingerprint(sourcePath);
        document.flameGraph = flameGraph;
        Files.writeString(flameGraphFile, GSON.toJson(document), StandardCharsets.UTF_8);
    }

    private Path fileDirectory(Path sourcePath) {
        return rootDirectory
                .resolve(FILES_DIR)
                .resolve(sha256(sourcePath.toAbsolutePath().normalize().toString()));
    }

    private Path snapshotFile(Path sourcePath) {
        return fileDirectory(sourcePath).resolve(SNAPSHOT_FILE);
    }

    private Path flameGraphFile(Path sourcePath, String dimensionKey) {
        return fileDirectory(sourcePath).resolve(FLAME_GRAPHS_DIR).resolve(safeFileName(dimensionKey) + ".json");
    }

    private String fingerprint(Path sourcePath) throws IOException {
        Path normalized = sourcePath.toAbsolutePath().normalize();
        return normalized
                + "|"
                + Files.size(normalized)
                + "|"
                + Files.getLastModifiedTime(normalized).toMillis();
    }

    private void ensureDirectory(Path directory) throws IOException {
        if (directory != null) {
            Files.createDirectories(directory);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path candidate : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static String safeFileName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    record CachedSnapshot(String fingerprint, Metadata metadata, AnalysisResult result) {}

    private static final class SnapshotDocument {
        String fingerprint;
        Metadata metadata;
        AnalysisResult result;
        long updatedAtEpochMillis;
    }

    private static final class FlameGraphDocument {
        String fingerprint;
        FlameGraph flameGraph;
    }
}
