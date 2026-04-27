package com.alibaba.arthas.idea.workbench.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jifa.jfr.model.AnalysisResult;
import org.eclipse.jifa.jfr.model.Filter;
import org.eclipse.jifa.jfr.model.PerfDimension;
import org.eclipse.jifa.jfr.vo.FlameGraph;
import org.eclipse.jifa.jfr.vo.Metadata;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JfrAnalysisCacheTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void defaultRootDirectoryUsesDedicatedEmbeddedJfrPath() {
        Path userHome = temporaryFolder.getRoot().toPath();

        assertEquals(
                userHome.resolve(".arthas-workbench-plugin").resolve("jfr"),
                JfrAnalysisCache.defaultRootDirectory(userHome));
    }

    @Test
    public void shouldPersistSnapshotAndFlameGraphWithoutTouchingWebCacheDirectory() throws Exception {
        Path cacheRoot = temporaryFolder.newFolder("embedded-jfr-cache").toPath();
        Path source = temporaryFolder.newFile("sample.jfr").toPath();
        Files.writeString(source, "fake-jfr-content");

        JfrAnalysisCache cache = new JfrAnalysisCache(cacheRoot);
        Metadata metadata = new Metadata();
        metadata.setPerfDimensions(new PerfDimension[] {
            PerfDimension.of("CPU Time", "CPU Time", new Filter[] {Filter.of("Thread", "Thread")}),
        });
        AnalysisResult result = new AnalysisResult();
        FlameGraph flameGraph = new FlameGraph();
        flameGraph.setSymbolTable(Map.of(1, "com.demo.Service.run"));
        flameGraph.setData(new Object[][] {
            {new String[] {"1"}, 42L, "worker-1"},
        });

        cache.storeSnapshot(source, metadata, result);
        cache.storeFlameGraph(source, "CPU Time", flameGraph);

        JfrAnalysisCache.CachedSnapshot snapshot = cache.loadSnapshot(source);
        FlameGraph cachedFlameGraph = cache.loadFlameGraph(source, "CPU Time");

        assertNotNull(snapshot);
        assertEquals(1, snapshot.metadata().getPerfDimensions().length);
        assertNotNull(cachedFlameGraph);
        assertEquals(42L, ((Number) cachedFlameGraph.getData()[0][1]).longValue());
        assertFalse(Files.exists(cacheRoot.resolveSibling("jifa")));
    }

    @Test
    public void shouldIgnoreSnapshotWhenSourceFingerprintChanges() throws Exception {
        Path cacheRoot = temporaryFolder.newFolder("embedded-jfr-cache-update").toPath();
        Path source = temporaryFolder.newFile("changed.jfr").toPath();
        Files.writeString(source, "first");

        JfrAnalysisCache cache = new JfrAnalysisCache(cacheRoot);
        cache.storeSnapshot(source, new Metadata(), new AnalysisResult());

        Files.writeString(source, "second-version");

        assertNull(cache.loadSnapshot(source));
        assertNull(cache.loadFlameGraph(source, "CPU Time"));
    }

    @Test
    public void shouldTreatMalformedSnapshotAsCacheMiss() throws Exception {
        Path cacheRoot = temporaryFolder.newFolder("embedded-jfr-cache-invalid").toPath();
        Path source = temporaryFolder.newFile("invalid.jfr").toPath();
        Files.writeString(source, "fake-jfr-content");

        JfrAnalysisCache cache = new JfrAnalysisCache(cacheRoot);
        Path snapshotDirectory = cacheRoot
                .resolve("files")
                .resolve(sha256(source.toAbsolutePath().normalize().toString()));
        Files.createDirectories(snapshotDirectory);
        Files.writeString(
                snapshotDirectory.resolve("snapshot.json"),
                "{\"fingerprint\":\""
                        + source.toAbsolutePath().normalize()
                        + "|"
                        + Files.size(source)
                        + "|"
                        + Files.getLastModifiedTime(source).toMillis()
                        + "\",\"metadata\":{},\"result\":{\"cpuTime\":{\"list\":[{\"samples\":\"invalid\"}]}}}",
                java.nio.charset.StandardCharsets.UTF_8);

        assertNull(cache.loadSnapshot(source));
        assertFalse(Files.exists(snapshotDirectory));
    }

    private String sha256(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of()
                .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
