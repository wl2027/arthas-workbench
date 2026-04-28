package com.alibaba.arthas.idea.workbench.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JifaWebRuntimeServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void defaultRootDirectoryUsesPluginScopedJifaPath() {
        Path userHome = temporaryFolder.getRoot().toPath();

        Path root = JifaWebRuntimeService.defaultRootDirectory(userHome);

        assertEquals(userHome.resolve(".arthas-workbench-plugin").resolve("jifa"), root);
    }

    @Test
    public void discoverArthasOutputDirectoriesFindsRootAndNestedDirectoriesWithinDepth() throws IOException {
        Path projectRoot = temporaryFolder.newFolder("demo-project").toPath();
        Path direct = Files.createDirectories(projectRoot.resolve("arthas-output"));
        Path nested = Files.createDirectories(projectRoot.resolve("module-a").resolve("arthas-output"));
        Path tooDeep = Files.createDirectories(
                projectRoot.resolve("a").resolve("b").resolve("c").resolve("d").resolve("arthas-output"));

        List<Path> directories = JifaWebRuntimeService.discoverArthasOutputDirectories(List.of(projectRoot));

        assertTrue(directories.contains(direct.toAbsolutePath().normalize()));
        assertTrue(directories.contains(nested.toAbsolutePath().normalize()));
        assertFalse(directories.contains(tooDeep.toAbsolutePath().normalize()));
    }

    @Test
    public void shouldSummarizeAndClearJifaCacheDirectories() throws Exception {
        Path root = temporaryFolder.newFolder("jifa-cache-root").toPath();
        JifaWebRuntimeService service = new JifaWebRuntimeService(root);

        Path storageFile = root.resolve("storage/heap-dump/sample.hprof");
        Path importIndexFile = root.resolve("meta/import-index.json");
        Path logFile = root.resolve("logs/jifa-server.log");
        Path runtimeFile = root.resolve("runtime/dev-snapshot/arthas-jifa-server-helper.jar");

        Files.createDirectories(storageFile.getParent());
        Files.createDirectories(importIndexFile.getParent());
        Files.createDirectories(logFile.getParent());
        Files.createDirectories(runtimeFile.getParent());
        Files.writeString(storageFile, "heap-dump-data");
        Files.writeString(importIndexFile, "{\"version\":1,\"entries\":{\"sample\":{}}}");
        Files.writeString(logFile, "server-log-data");
        writeDummyJar(runtimeFile);

        JifaWebRuntimeService.CacheSummary summary = service.loadCacheSummary();

        assertEquals(root, summary.rootDirectory());
        assertEquals(4L, summary.totalFileCount());
        assertEquals(1, summary.importedEntryCount());
        assertTrue(summary.totalSizeBytes() > 0);
        assertEquals(1L, summary.runtime().fileCount());

        JifaWebRuntimeService.CacheSummary afterMetadataClear = service.clearMetadata();
        assertEquals(2L, afterMetadataClear.totalFileCount());
        assertEquals(0, afterMetadataClear.importedEntryCount());
        assertTrue(Files.exists(root.resolve("logs/jifa-server.log")));
        assertTrue(Files.exists(runtimeFile));
        assertFalse(Files.exists(storageFile));
        assertFalse(Files.exists(importIndexFile));

        JifaWebRuntimeService.CacheSummary afterAllClear = service.clearAll();
        assertEquals(0L, afterAllClear.totalFileCount());
        assertTrue(Files.isDirectory(root.resolve("storage")));
        assertTrue(Files.isDirectory(root.resolve("meta")));
        assertTrue(Files.isDirectory(root.resolve("logs")));
        assertTrue(Files.isDirectory(root.resolve("runtime")));
        assertFalse(Files.exists(logFile));
        assertFalse(Files.exists(runtimeFile));
    }

    @Test
    public void shouldReuseAndRefreshImportedFilesAcrossCrudCycles() throws Exception {
        Path root = temporaryFolder.newFolder("jifa-runtime-root").toPath();
        JifaWebRuntimeService service = new JifaWebRuntimeService(root);
        FakeJifaServer server = new FakeJifaServer();
        try {
            Path threadDump = copyFixture(
                    temporaryFolder.newFile("sample-thread-dump.log").toPath(), threadDumpContent());
            Path gcLog = copyFixture(temporaryFolder.newFile("sample-gc.log").toPath(), gcLogContent());

            JifaWebRuntimeService.SyncSummary initial =
                    sync(service, server.serverHandle(), List.of(threadDump, gcLog));
            assertEquals(2, initial.discovered());
            assertEquals(2, initial.uploaded());
            assertEquals(0, initial.reused());
            assertEquals(0, initial.deleted());
            assertEquals(2, server.remoteFileCount());
            assertEquals(2, loadEntries(service).size());

            long firstThreadRemoteId = remoteIdOf(loadEntries(service), threadDump);

            JifaWebRuntimeService.SyncSummary reused = sync(service, server.serverHandle(), List.of(threadDump, gcLog));
            assertEquals(2, reused.discovered());
            assertEquals(0, reused.uploaded());
            assertEquals(2, reused.reused());
            assertEquals(0, reused.deleted());
            assertEquals(2, server.remoteFileCount());

            Files.writeString(threadDump, "\n# updated", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Files.setLastModifiedTime(threadDump, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));

            JifaWebRuntimeService.SyncSummary updated =
                    sync(service, server.serverHandle(), List.of(threadDump, gcLog));
            assertEquals(2, updated.discovered());
            assertEquals(1, updated.uploaded());
            assertEquals(1, updated.reused());
            assertEquals(1, updated.deleted());
            assertEquals(2, server.remoteFileCount());

            long secondThreadRemoteId = remoteIdOf(loadEntries(service), threadDump);
            assertNotEquals(firstThreadRemoteId, secondThreadRemoteId);

            Files.delete(gcLog);

            JifaWebRuntimeService.SyncSummary deleted = sync(service, server.serverHandle(), List.of(threadDump));
            assertEquals(1, deleted.discovered());
            assertEquals(0, deleted.uploaded());
            assertEquals(1, deleted.reused());
            assertEquals(1, deleted.deleted());
            assertEquals(1, server.remoteFileCount());
            assertEquals(1, loadEntries(service).size());
        } finally {
            server.close();
            service.dispose();
        }
    }

    @Test
    public void shouldKeepPreviouslyImportedExternalFilesInManagedSourceCollection() throws Exception {
        Path root = temporaryFolder.newFolder("jifa-runtime-root-external").toPath();
        JifaWebRuntimeService service = new JifaWebRuntimeService(root);
        FakeJifaServer server = new FakeJifaServer();
        try {
            Path externalThreadDump = copyFixture(
                    temporaryFolder.newFile("external-thread-dump.log").toPath(), threadDumpContent());

            JifaWebRuntimeService.SyncSummary initial =
                    sync(service, server.serverHandle(), List.of(externalThreadDump));
            assertEquals(1, initial.uploaded());

            List<Path> managedSources = collectManagedSourcePaths(service, List.of());
            assertEquals(List.of(externalThreadDump.toAbsolutePath().normalize()), managedSources);
        } finally {
            server.close();
            service.dispose();
        }
    }

    @Test
    public void shouldDownloadAndCacheExternalHelperJar() throws Exception {
        Path root = temporaryFolder.newFolder("jifa-runtime-root-download").toPath();
        JifaWebRuntimeService service = new JifaWebRuntimeService(root);
        Path sourceJar = temporaryFolder.newFile("helper-source.jar").toPath();
        writeDummyJar(sourceJar);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/helper.jar", exchange -> {
            byte[] bytes = Files.readAllBytes(sourceJar);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String originalUrl = System.getProperty("arthas.workbench.jifa.helper.url");
        String originalVersion = System.getProperty("arthas.workbench.jifa.helper.version");
        try {
            System.setProperty(
                    "arthas.workbench.jifa.helper.url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/helper.jar");
            System.setProperty("arthas.workbench.jifa.helper.version", "unit-test-version");

            Path cached = service.ensureCachedHelperJar();

            assertEquals(
                    root.resolve("runtime").resolve("unit-test-version").resolve("arthas-jifa-server-helper.jar"),
                    cached);
            assertTrue(Files.isRegularFile(cached));
            assertTrue(Files.size(cached) > 0L);
        } finally {
            restoreSystemProperty("arthas.workbench.jifa.helper.url", originalUrl);
            restoreSystemProperty("arthas.workbench.jifa.helper.version", originalVersion);
            server.stop(0);
            service.dispose();
        }
    }

    @Test
    public void shouldPreferConfiguredHelperDirectoryWhenOfflineHelperIsProvided() throws Exception {
        Path root = temporaryFolder.newFolder("jifa-runtime-root-configured").toPath();
        JifaWebRuntimeService service = new JifaWebRuntimeService(root);
        Path helperDirectory =
                temporaryFolder.newFolder("configured-helper-dir").toPath();
        Path helperJar = helperDirectory.resolve("arthas-jifa-server-helper.jar");
        writeDummyJar(helperJar);

        String originalPath = System.getProperty("arthas.workbench.jifa.helper.path");
        try {
            System.setProperty("arthas.workbench.jifa.helper.path", helperDirectory.toString());
            assertEquals(helperJar, invokeResolveHelperJar(service));
        } finally {
            restoreSystemProperty("arthas.workbench.jifa.helper.path", originalPath);
            service.dispose();
        }
    }

    @Test
    public void shouldResolveJavaExecutableFromJavaHomeInsteadOfCurrentProcessCommand() throws Exception {
        Path fakeJavaHome = temporaryFolder.newFolder("fake-java-home").toPath();
        Path fakeJava = Files.createDirectories(fakeJavaHome.resolve("bin")).resolve("java");
        Files.writeString(fakeJava, "#!/bin/sh\n");

        Path resolved = JifaWebRuntimeService.resolveJavaExecutable(fakeJavaHome, "Mac OS X");

        assertEquals(fakeJava.toAbsolutePath().normalize(), resolved);
    }

    private Path copyFixture(Path target, String content) throws IOException {
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private String threadDumpContent() {
        return """
                2026-04-27 20:00:00
                Full thread dump OpenJDK 64-Bit Server VM:

                "main" #1 prio=5 os_prio=31 cpu=10.00ms elapsed=1.23s tid=0x1 nid=0x2 runnable  [0x0000000000000000]
                   java.lang.Thread.State: RUNNABLE
                """;
    }

    private String gcLogContent() {
        return """
                [0.123s][info][gc] Using G1
                [0.456s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 32M->8M(256M) 4.567ms
                """;
    }

    private void writeDummyJar(Path target) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    private Path invokeResolveHelperJar(JifaWebRuntimeService service) throws Exception {
        Method method = JifaWebRuntimeService.class.getDeclaredMethod("resolveHelperJar");
        method.setAccessible(true);
        return (Path) method.invoke(service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadEntries(JifaWebRuntimeService service) throws Exception {
        Method loadImportIndex = JifaWebRuntimeService.class.getDeclaredMethod("loadImportIndex");
        loadImportIndex.setAccessible(true);
        Object index = loadImportIndex.invoke(service);
        Field entriesField = index.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        return (Map<String, Object>) entriesField.get(index);
    }

    private long remoteIdOf(Map<String, Object> entries, Path sourcePath) throws Exception {
        Object entry = entries.get(sourcePath.toAbsolutePath().normalize().toString());
        assertNotNull(entry);
        Field remoteIdField = entry.getClass().getDeclaredField("remoteId");
        remoteIdField.setAccessible(true);
        return remoteIdField.getLong(entry);
    }

    private JifaWebRuntimeService.SyncSummary sync(JifaWebRuntimeService service, Object serverHandle, List<Path> paths)
            throws Exception {
        Method ensureDirectories = JifaWebRuntimeService.class.getDeclaredMethod("ensureDirectories");
        ensureDirectories.setAccessible(true);
        ensureDirectories.invoke(service);

        Method toSourceFile = JifaWebRuntimeService.class.getDeclaredMethod("toSourceFile", Path.class, Map.class);
        toSourceFile.setAccessible(true);
        List<Object> sources = new java.util.ArrayList<>();
        for (Path path : paths) {
            Object source = toSourceFile.invoke(service, path.toAbsolutePath().normalize(), null);
            assertNotNull(source);
            sources.add(source);
        }

        Method syncSources = JifaWebRuntimeService.class.getDeclaredMethod(
                "syncSources",
                serverHandle.getClass(),
                List.class,
                com.intellij.openapi.progress.ProgressIndicator.class);
        syncSources.setAccessible(true);
        Object indicator = Proxy.newProxyInstance(
                JifaWebRuntimeServiceTest.class.getClassLoader(),
                new Class<?>[] {com.intellij.openapi.progress.ProgressIndicator.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Object syncResult = syncSources.invoke(service, serverHandle, sources, indicator);
        Method summaryAccessor = syncResult.getClass().getDeclaredMethod("summary");
        summaryAccessor.setAccessible(true);
        return (JifaWebRuntimeService.SyncSummary) summaryAccessor.invoke(syncResult);
    }

    private List<Path> collectManagedSourcePaths(JifaWebRuntimeService service, Collection<Path> additionalSources)
            throws Exception {
        Method collectManagedSources =
                JifaWebRuntimeService.class.getDeclaredMethod("collectManagedSources", Set.class);
        collectManagedSources.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> sources = (List<Object>) collectManagedSources.invoke(service, Set.copyOf(additionalSources));
        List<Path> result = new java.util.ArrayList<>();
        for (Object source : sources) {
            Method sourcePathAccessor = source.getClass().getDeclaredMethod("sourcePath");
            sourcePathAccessor.setAccessible(true);
            result.add((Path) sourcePathAccessor.invoke(source));
        }
        return result;
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        return null;
    }

    private static final class FakeJifaServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicLong nextId = new AtomicLong(1000);
        private final Map<Long, RemoteFileRecord> files = new LinkedHashMap<>();

        private FakeJifaServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jifa-api/files", this::handleFiles);
            server.start();
        }

        Object serverHandle() throws Exception {
            Class<?> handleClass =
                    Class.forName("com.alibaba.arthas.idea.workbench.service.JifaWebRuntimeService$ServerHandle");
            Constructor<?> constructor = handleClass.getDeclaredConstructor(int.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(server.getAddress().getPort(), 0L);
        }

        int remoteFileCount() {
            return files.size();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleFiles(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/jifa-api/files".equals(path) && "GET".equals(method)) {
                handleList(exchange);
                return;
            }
            if ("/jifa-api/files/upload".equals(path) && "POST".equals(method)) {
                handleUpload(exchange);
                return;
            }
            if (path.startsWith("/jifa-api/files/")) {
                long id = Long.parseLong(path.substring("/jifa-api/files/".length()));
                if ("GET".equals(method)) {
                    handleGet(exchange, id);
                    return;
                }
                if ("DELETE".equals(method)) {
                    handleDelete(exchange, id);
                    return;
                }
            }
            send(exchange, 404, "{\"error\":\"not found\"}");
        }

        private void handleList(HttpExchange exchange) throws IOException {
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            response.write("{\"page\":1,\"pageSize\":500,\"totalSize\":".getBytes(StandardCharsets.UTF_8));
            response.write(String.valueOf(files.size()).getBytes(StandardCharsets.UTF_8));
            response.write(",\"data\":[".getBytes(StandardCharsets.UTF_8));

            boolean first = true;
            for (RemoteFileRecord record : files.values()) {
                if (!first) {
                    response.write(',');
                }
                response.write(record.toJson().getBytes(StandardCharsets.UTF_8));
                first = false;
            }
            response.write("]}".getBytes(StandardCharsets.UTF_8));
            send(exchange, 200, response.toString(StandardCharsets.UTF_8));
        }

        private void handleUpload(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            long id = nextId.incrementAndGet();
            String type = queryValue(exchange.getRequestURI().getRawQuery(), "type");
            RemoteFileRecord record =
                    new RemoteFileRecord(id, "remote-" + id, "upload-" + id, type == null ? "UNKNOWN" : type, 0L);
            files.put(id, record);
            send(exchange, 200, String.valueOf(id));
        }

        private void handleGet(HttpExchange exchange, long id) throws IOException {
            RemoteFileRecord record = files.get(id);
            if (record == null) {
                send(exchange, 404, "{\"error\":\"missing\"}");
                return;
            }
            send(exchange, 200, record.toJson());
        }

        private void handleDelete(HttpExchange exchange, long id) throws IOException {
            files.remove(id);
            send(exchange, 204, "");
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (!body.isEmpty()) {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private String queryValue(String query, String key) {
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                if (key.equals(pair.substring(0, separator))) {
                    return pair.substring(separator + 1);
                }
            }
            return null;
        }

        private record RemoteFileRecord(long id, String uniqueName, String originalName, String type, long size) {
            String toJson() {
                return "{\"id\":"
                        + id
                        + ",\"uniqueName\":\""
                        + uniqueName
                        + "\",\"originalName\":\""
                        + originalName
                        + "\",\"type\":\""
                        + type
                        + "\",\"size\":"
                        + size
                        + ",\"createdTime\":\"2026-04-27T00:00:00Z\"}";
            }
        }
    }
}
