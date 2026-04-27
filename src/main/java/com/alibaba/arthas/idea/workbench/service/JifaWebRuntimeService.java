package com.alibaba.arthas.idea.workbench.service;

import com.alibaba.arthas.idea.workbench.analysis.JifaAnalysisFacade;
import com.alibaba.arthas.idea.workbench.analysis.JifaArtifactDescriptor;
import com.alibaba.arthas.idea.workbench.analysis.JifaArtifactType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 管理插件内置的 Jifa Web 本地服务、文件同步与缓存目录。
 */
@Service(Service.Level.APP)
public final class JifaWebRuntimeService implements Disposable {

    private static final String PLUGIN_ID = "com.github.wl2027.arthasworkbench";
    private static final String PLUGIN_HOME_DIR = ".arthas-workbench-plugin";
    private static final String JIFA_ROOT_DIR = "jifa";
    private static final String STORAGE_DIR = "storage";
    private static final String META_DIR = "meta";
    private static final String LOG_DIR = "logs";
    private static final String IMPORT_INDEX_FILE = "import-index.json";
    private static final String SERVER_STATE_FILE = "server-state.json";
    private static final String SERVER_LOG_FILE = "jifa-server.log";
    private static final String HELPER_JAR_NAME = "arthas-jifa-server-helper.jar";
    private static final int SOURCE_SCAN_DEPTH = 4;
    private static final int FILE_LIST_PAGE_SIZE = 500;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(90);

    private final Object monitor = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path rootDirectory;

    private volatile ServerHandle activeServer;
    private volatile Process serverProcess;

    public JifaWebRuntimeService() {
        this(defaultRootDirectory(Path.of(System.getProperty("user.home"))));
    }

    JifaWebRuntimeService(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
    }

    public LaunchResult prepareHomePage(ProgressIndicator indicator) throws Exception {
        ServerHandle server = ensureServer(indicator);
        SyncResult syncResult = syncSources(server, collectManagedSources(Set.of()), indicator);
        return new LaunchResult(server.baseUrl(), syncResult.summary);
    }

    public LaunchResult prepareAnalysisPage(Path target, ProgressIndicator indicator) throws Exception {
        SourceFile targetSource = toSourceFile(normalize(target), null);
        if (targetSource == null) {
            throw new IllegalStateException("Unsupported Jifa artifact: " + target);
        }
        ServerHandle server = ensureServer(indicator);
        SyncResult syncResult = syncSources(server, collectManagedSources(Set.of(targetSource.sourcePath)), indicator);
        ImportedEntry imported = syncResult.index.entries.get(targetSource.sourceKey);
        if (imported == null) {
            throw new IllegalStateException("Failed to import target into Jifa Web: " + target);
        }
        return new LaunchResult(
                server.baseUrl() + "/" + imported.analysisPath + "/" + imported.remoteUniqueName, syncResult.summary);
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public CacheSummary loadCacheSummary() throws IOException {
        ensureDirectories();
        DirectorySummary storage = summarizeDirectory(storageDirectory());
        DirectorySummary metadata = summarizeDirectory(metaDirectory());
        DirectorySummary logs = summarizeDirectory(logsDirectory());
        ImportedIndex importIndex = loadImportIndex();
        ServerHandle healthyServer = currentHealthyServer();
        return new CacheSummary(
                rootDirectory(),
                storage,
                metadata,
                logs,
                storage.sizeBytes + metadata.sizeBytes + logs.sizeBytes,
                storage.fileCount + metadata.fileCount + logs.fileCount,
                importIndex.entries.size(),
                healthyServer != null,
                healthyServer == null ? -1 : healthyServer.port,
                System.currentTimeMillis());
    }

    /**
     * 仅清理日志文件，保留当前分析缓存与服务状态。
     */
    public CacheSummary clearLogs() throws IOException {
        synchronized (monitor) {
            ensureDirectories();
            boolean preserveLiveServerLog = currentHealthyServer() != null;
            clearLogsDirectory(preserveLiveServerLog);
            return loadCacheSummary();
        }
    }

    /**
     * 重建元数据与分析索引。为了避免 metadata 与 storage 脱节导致重复导入，这里会一并清空 storage。
     */
    public CacheSummary clearMetadata() throws IOException {
        synchronized (monitor) {
            stopManagedServer();
            ensureDirectories();
            deleteDirectoryContents(storageDirectory());
            deleteDirectoryContents(metaDirectory());
            return loadCacheSummary();
        }
    }

    public CacheSummary clearAll() throws IOException {
        synchronized (monitor) {
            stopManagedServer();
            ensureDirectories();
            deleteDirectoryContents(storageDirectory());
            deleteDirectoryContents(metaDirectory());
            deleteDirectoryContents(logsDirectory());
            return loadCacheSummary();
        }
    }

    @Override
    public void dispose() {
        synchronized (monitor) {
            destroyProcess(serverProcess);
            serverProcess = null;
            activeServer = null;
        }
    }

    static Path defaultRootDirectory(Path userHome) {
        Objects.requireNonNull(userHome, "userHome");
        return userHome.resolve(PLUGIN_HOME_DIR).resolve(JIFA_ROOT_DIR);
    }

    static List<Path> discoverArthasOutputDirectories(Collection<Path> projectRoots) {
        Set<Path> directories = new LinkedHashSet<>();
        for (Path root : projectRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.find(
                                root,
                                SOURCE_SCAN_DEPTH,
                                (candidate, attributes) -> attributes.isDirectory()
                                        && "arthas-output"
                                                .equals(candidate.getFileName().toString()))
                        .forEach(path -> directories.add(path.toAbsolutePath().normalize()));
            } catch (IOException ignored) {
                // 忽略单个工程目录的扫描失败，不影响其他工程。
            }
        }
        return new ArrayList<>(directories);
    }

    private Path storageDirectory() {
        return rootDirectory().resolve(STORAGE_DIR);
    }

    private Path metaDirectory() {
        return rootDirectory().resolve(META_DIR);
    }

    private Path logsDirectory() {
        return rootDirectory().resolve(LOG_DIR);
    }

    private Path importIndexFile() {
        return metaDirectory().resolve(IMPORT_INDEX_FILE);
    }

    private Path serverStateFile() {
        return metaDirectory().resolve(SERVER_STATE_FILE);
    }

    private Path serverLogFile() {
        return logsDirectory().resolve(SERVER_LOG_FILE);
    }

    private ServerHandle currentHealthyServer() throws IOException {
        synchronized (monitor) {
            if (activeServer != null && isServerHealthy(activeServer.port)) {
                return activeServer;
            }
            activeServer = null;

            ServerHandle persisted = loadServerState();
            if (persisted != null && isServerHealthy(persisted.port)) {
                activeServer = persisted;
                return persisted;
            }
            return null;
        }
    }

    private ServerHandle ensureServer(ProgressIndicator indicator) throws Exception {
        synchronized (monitor) {
            ensureDirectories();
            if (activeServer != null && isServerHealthy(activeServer.port)) {
                return activeServer;
            }

            ServerHandle persisted = loadServerState();
            if (persisted != null && isServerHealthy(persisted.port)) {
                activeServer = persisted;
                return persisted;
            }

            if (persisted != null) {
                destroyProcessHandle(persisted.pid);
            }
            destroyProcess(serverProcess);
            serverProcess = null;

            indicator.setText("Starting local Jifa Web service...");
            int port = chooseRandomPort();
            Path helperJar = resolveHelperJar();
            Process process = startServerProcess(helperJar, port);
            serverProcess = process;

            long deadline = System.nanoTime() + SERVER_START_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("Jifa Web exited early. See log: " + serverLogFile());
                }
                if (isServerHealthy(port)) {
                    activeServer = new ServerHandle(port, process.pid());
                    saveServerState(activeServer);
                    return activeServer;
                }
                TimeUnit.MILLISECONDS.sleep(500);
            }

            destroyProcess(process);
            serverProcess = null;
            throw new IllegalStateException("Timed out waiting for Jifa Web to become ready.");
        }
    }

    private Process startServerProcess(Path helperJar, int port) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(currentJavaExecutable());
        command.add("-jar");
        command.add(helperJar.toString());
        command.add("--jifa.role=standalone-worker");
        command.add("--jifa.storage-path=" + storageDirectory());
        command.add("--jifa.port=" + port);
        command.add("--jifa.open-browser-when-ready=false");
        command.add("--jifa.allow-login=false");
        command.add("--jifa.allow-anonymous-access=true");
        command.add("--spring.servlet.multipart.max-file-size=10GB");
        command.add("--spring.servlet.multipart.max-request-size=10GB");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(serverLogFile().toFile()));
        return builder.start();
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(rootDirectory());
        Files.createDirectories(storageDirectory());
        Files.createDirectories(metaDirectory());
        Files.createDirectories(logsDirectory());
    }

    private DirectorySummary summarizeDirectory(Path directory) throws IOException {
        ensureDirectory(directory);
        long sizeBytes = 0L;
        long fileCount = 0L;
        long latestModifiedEpochMillis = 0L;
        try (var stream = Files.walk(directory)) {
            for (Path candidate : stream.toList()) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                fileCount++;
                sizeBytes += Files.size(candidate);
                latestModifiedEpochMillis = Math.max(
                        latestModifiedEpochMillis,
                        Files.getLastModifiedTime(candidate).toMillis());
            }
        }
        return new DirectorySummary(directory, sizeBytes, fileCount, latestModifiedEpochMillis);
    }

    private void clearLogsDirectory(boolean preserveServerLogFile) throws IOException {
        ensureDirectory(logsDirectory());
        try (var stream = Files.list(logsDirectory())) {
            for (Path candidate : stream.toList()) {
                if (preserveServerLogFile && candidate.equals(serverLogFile()) && Files.isRegularFile(candidate)) {
                    Files.writeString(candidate, "", StandardCharsets.UTF_8);
                    continue;
                }
                deleteRecursively(candidate);
            }
        }
        ensureDirectory(logsDirectory());
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        ensureDirectory(directory);
        try (var stream = Files.list(directory)) {
            for (Path candidate : stream.toList()) {
                deleteRecursively(candidate);
            }
        }
        ensureDirectory(directory);
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

    private void ensureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    private SyncResult syncSources(ServerHandle server, List<SourceFile> sourceFiles, ProgressIndicator indicator)
            throws Exception {
        ImportedIndex index = loadImportIndex();
        Map<String, RemoteFileView> remoteFiles = listAllRemoteFiles(server, indicator);
        Set<String> activeKeys = new LinkedHashSet<>();

        int uploaded = 0;
        int reused = 0;
        int deleted = 0;

        indicator.setText("Syncing managed files to Jifa Web...");
        for (int i = 0; i < sourceFiles.size(); i++) {
            SourceFile source = sourceFiles.get(i);
            indicator.setFraction(sourceFiles.isEmpty() ? 0.0 : (double) i / sourceFiles.size());
            indicator.setText2(source.displayName);

            activeKeys.add(source.sourceKey);
            ImportedEntry existing = index.entries.get(source.sourceKey);
            if (existing != null && existing.matches(source) && remoteFiles.containsKey(existing.remoteUniqueName)) {
                existing.lastSeenAtEpochMillis = System.currentTimeMillis();
                reused++;
                continue;
            }

            if (existing != null) {
                if (remoteFiles.containsKey(existing.remoteUniqueName)) {
                    deleteRemoteFile(server, existing.remoteId);
                    deleted++;
                    remoteFiles.remove(existing.remoteUniqueName);
                }
                index.entries.remove(source.sourceKey);
            }

            RemoteFileView remote = uploadFile(server, source);
            ImportedEntry imported = ImportedEntry.from(source, remote);
            index.entries.put(source.sourceKey, imported);
            remoteFiles.put(remote.uniqueName, remote);
            uploaded++;
        }

        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, ImportedEntry> entry : index.entries.entrySet()) {
            if (activeKeys.contains(entry.getKey())) {
                continue;
            }
            staleKeys.add(entry.getKey());
        }
        for (String staleKey : staleKeys) {
            ImportedEntry stale = index.entries.remove(staleKey);
            if (stale != null && remoteFiles.containsKey(stale.remoteUniqueName)) {
                deleteRemoteFile(server, stale.remoteId);
                deleted++;
            }
        }

        index.updatedAtEpochMillis = System.currentTimeMillis();
        saveImportIndex(index);
        indicator.setFraction(1.0);
        return new SyncResult(index, new SyncSummary(sourceFiles.size(), reused, uploaded, deleted));
    }

    private List<SourceFile> collectManagedSources(Set<Path> additionalSources) throws IOException {
        Map<String, SourceFile> sources = new LinkedHashMap<>();
        Map<Path, String> projectNames = new LinkedHashMap<>();
        List<Path> projectRoots = new ArrayList<>();
        ImportedIndex importIndex = loadImportIndex();
        ProjectManager projectManager = null;
        try {
            projectManager = ProjectManager.getInstance();
        } catch (Throwable ignored) {
            // 在轻量测试或应用尚未完全就绪时，允许继续处理显式导入文件。
        }
        if (projectManager != null) {
            for (Project project : projectManager.getOpenProjects()) {
                String basePath = project.getBasePath();
                if (basePath == null || basePath.isBlank()) {
                    continue;
                }
                Path root = normalize(Path.of(basePath));
                projectRoots.add(root);
                projectNames.put(root, project.getName());
            }
        }

        List<Path> arthasOutputDirectories = discoverArthasOutputDirectories(projectRoots);
        for (Path arthasOutputDir : arthasOutputDirectories) {
            try (var stream = Files.list(arthasOutputDir)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        SourceFile source = toSourceFile(normalize(path), projectNames);
                        if (source != null) {
                            sources.put(source.sourceKey, source);
                        }
                    } catch (IOException ignored) {
                        // 跳过单个异常文件。
                    }
                });
            }
        }

        for (ImportedEntry entry : importIndex.entries.values()) {
            if (entry == null || entry.sourcePath == null || entry.sourcePath.isBlank()) {
                continue;
            }
            Path persistedSourcePath;
            try {
                persistedSourcePath = normalize(Path.of(entry.sourcePath));
            } catch (InvalidPathException ignored) {
                continue;
            }
            if (!Files.isRegularFile(persistedSourcePath)
                    || isUnderAnyDirectory(persistedSourcePath, arthasOutputDirectories)) {
                continue;
            }
            SourceFile source = toSourceFile(persistedSourcePath, projectNames);
            if (source != null) {
                sources.put(source.sourceKey, source);
            }
        }

        for (Path additionalSource : additionalSources) {
            if (!Files.isRegularFile(additionalSource)) {
                continue;
            }
            SourceFile source = toSourceFile(normalize(additionalSource), projectNames);
            if (source != null) {
                sources.put(source.sourceKey, source);
            }
        }
        return new ArrayList<>(sources.values());
    }

    private SourceFile toSourceFile(Path sourcePath, Map<Path, String> projectNames) throws IOException {
        JifaArtifactDescriptor descriptor = JifaAnalysisFacade.detect(sourcePath);
        if (descriptor == null) {
            return null;
        }
        JifaWebFileType webFileType = JifaWebFileType.fromArtifact(descriptor.type());
        long size = Files.size(sourcePath);
        long lastModified = Files.getLastModifiedTime(sourcePath).toMillis();
        String normalizedPath = sourcePath.toString();
        String fingerprint = normalizedPath + "|" + size + "|" + lastModified;
        return new SourceFile(
                sourcePath,
                normalizedPath,
                webFileType,
                fingerprint,
                buildDisplayName(sourcePath, projectNames),
                size,
                lastModified);
    }

    private String buildDisplayName(Path sourcePath, Map<Path, String> projectNames) {
        if (projectNames != null) {
            for (Map.Entry<Path, String> entry : projectNames.entrySet()) {
                Path root = entry.getKey();
                if (sourcePath.startsWith(root)) {
                    Path relative = root.relativize(sourcePath);
                    return entry.getValue() + " - " + relative.toString().replace('\\', '/');
                }
            }
        }
        return sourcePath.toString();
    }

    private static boolean isUnderAnyDirectory(Path sourcePath, Collection<Path> directories) {
        for (Path directory : directories) {
            if (sourcePath.startsWith(directory)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, RemoteFileView> listAllRemoteFiles(ServerHandle server, ProgressIndicator indicator)
            throws Exception {
        Map<String, RemoteFileView> files = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            indicator.setText("Loading Jifa Web file index...");
            RemotePage<RemoteFileView> pageView = get(
                    server.baseUrl() + "/jifa-api/files?page=" + page + "&pageSize=" + FILE_LIST_PAGE_SIZE,
                    new TypeToken<RemotePage<RemoteFileView>>() {}.getType());
            if (pageView == null || pageView.data == null || pageView.data.isEmpty()) {
                break;
            }
            for (RemoteFileView file : pageView.data) {
                files.put(file.uniqueName, file);
            }
            if ((long) page * pageView.pageSize >= pageView.totalSize) {
                break;
            }
            page++;
        }
        return files;
    }

    private RemoteFileView uploadFile(ServerHandle server, SourceFile source) throws Exception {
        String boundary = "----ArthasWorkbenchBoundary" + System.nanoTime();
        String lineEnd = "\r\n";
        String preamble = "--" + boundary + lineEnd
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + escapeQuoted(source.displayName)
                + "\"" + lineEnd
                + "Content-Type: application/octet-stream" + lineEnd + lineEnd;
        String ending = lineEnd + "--" + boundary + "--" + lineEnd;

        String query = "type=" + URLEncoder.encode(source.fileType.requestType, StandardCharsets.UTF_8);
        URI uri = URI.create(ensureTrailingSlash(server.baseUrl()) + "jifa-api/files/upload?" + query);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout((int) HTTP_TIMEOUT.toMillis());
        connection.setReadTimeout((int) HTTP_TIMEOUT.toMillis());
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setChunkedStreamingMode(16 * 1024);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(preamble.getBytes(StandardCharsets.UTF_8));
            try (InputStream input = Files.newInputStream(source.sourcePath)) {
                input.transferTo(output);
            }
            output.write(ending.getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        String responseBody = readConnectionBody(connection, code);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Upload failed: HTTP " + code + " - " + responseBody);
        }

        long fileId = Long.parseLong(responseBody.trim());
        return get(server.baseUrl() + "/jifa-api/files/" + fileId, RemoteFileView.class);
    }

    private void deleteRemoteFile(ServerHandle server, long remoteId) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/jifa-api/files/" + remoteId))
                        .timeout(HTTP_TIMEOUT)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status == 404) {
            return;
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    "Failed to delete remote Jifa file: HTTP " + status + " - " + response.body());
        }
    }

    private boolean isServerHealthy(int port) {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/jifa-api/health-check"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private <T> T get(String url, java.lang.reflect.Type type) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Request failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        return gson.fromJson(response.body(), type);
    }

    private ImportedIndex loadImportIndex() throws IOException {
        Path file = importIndexFile();
        if (!Files.exists(file)) {
            ImportedIndex index = new ImportedIndex();
            index.version = 1;
            return index;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        ImportedIndex index = gson.fromJson(json, ImportedIndex.class);
        if (index == null) {
            index = new ImportedIndex();
        }
        if (index.entries == null) {
            index.entries = new LinkedHashMap<>();
        }
        if (index.version <= 0) {
            index.version = 1;
        }
        return index;
    }

    private void saveImportIndex(ImportedIndex index) throws IOException {
        Files.writeString(importIndexFile(), gson.toJson(index), StandardCharsets.UTF_8);
    }

    private ServerHandle loadServerState() throws IOException {
        Path file = serverStateFile();
        if (!Files.exists(file)) {
            return null;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        PersistedServerState state = gson.fromJson(json, PersistedServerState.class);
        if (state == null || state.port <= 0) {
            return null;
        }
        return new ServerHandle(state.port, state.pid);
    }

    private void saveServerState(ServerHandle serverHandle) throws IOException {
        PersistedServerState state = new PersistedServerState();
        state.port = serverHandle.port;
        state.pid = serverHandle.pid;
        Files.writeString(serverStateFile(), gson.toJson(state), StandardCharsets.UTF_8);
    }

    private Path resolveHelperJar() {
        Path pluginJar = resolvePluginHelperJar();
        if (pluginJar != null && Files.isRegularFile(pluginJar)) {
            return pluginJar;
        }
        Path generatedJar = Path.of("build", "generated", "jifa-helper", HELPER_JAR_NAME)
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(generatedJar)) {
            return generatedJar;
        }
        Path workspaceJar = Path.of("jifa", "server", "build", "libs", "jifa.jar")
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(workspaceJar)) {
            return workspaceJar;
        }
        throw new IllegalStateException("Unable to locate bundled Jifa server helper jar.");
    }

    private Path resolvePluginHelperJar() {
        var pluginDescriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (pluginDescriptor == null) {
            return null;
        }
        return pluginDescriptor.getPluginPath().resolve("lib").resolve(HELPER_JAR_NAME);
    }

    private int chooseRandomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private String currentJavaExecutable() {
        return ProcessHandle.current().info().command().orElseGet(() -> {
            String executable =
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", executable).toString();
        });
    }

    private void stopManagedServer() throws IOException {
        synchronized (monitor) {
            ServerHandle running = activeServer;
            ServerHandle persisted = loadServerState();

            destroyProcess(serverProcess);
            serverProcess = null;

            if (running != null && running.pid > 0) {
                destroyProcessHandle(running.pid);
            }
            if (persisted != null && persisted.pid > 0 && (running == null || persisted.pid != running.pid)) {
                destroyProcessHandle(persisted.pid);
            }

            activeServer = null;
            Files.deleteIfExists(serverStateFile());
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String escapeQuoted(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readConnectionBody(HttpURLConnection connection, int code) throws IOException {
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String ensureTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    private void destroyProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void destroyProcessHandle(long pid) {
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.destroy();
            try {
                if (!handle.onExit().get(5, TimeUnit.SECONDS).isAlive()) {
                    return;
                }
            } catch (Exception ignored) {
                // 继续尝试强杀。
            }
            handle.destroyForcibly();
        });
    }

    public record LaunchResult(String url, SyncSummary summary) {}

    public record SyncSummary(int discovered, int reused, int uploaded, int deleted) {}

    public record DirectorySummary(Path path, long sizeBytes, long fileCount, long latestModifiedEpochMillis) {}

    public record CacheSummary(
            Path rootDirectory,
            DirectorySummary storage,
            DirectorySummary metadata,
            DirectorySummary logs,
            long totalSizeBytes,
            long totalFileCount,
            int importedEntryCount,
            boolean serverRunning,
            int serverPort,
            long generatedAtEpochMillis) {}

    private record SyncResult(ImportedIndex index, SyncSummary summary) {}

    private record ServerHandle(int port, long pid) {
        String baseUrl() {
            return "http://127.0.0.1:" + port;
        }
    }

    private record SourceFile(
            Path sourcePath,
            String sourceKey,
            JifaWebFileType fileType,
            String fingerprint,
            String displayName,
            long size,
            long lastModifiedEpochMillis) {}

    private enum JifaWebFileType {
        HEAP_DUMP("HEAP_DUMP", "heap-dump-analysis"),
        GC_LOG("GC_LOG", "gc-log-analysis"),
        THREAD_DUMP("THREAD_DUMP", "thread-dump-analysis"),
        JFR_FILE("JFR_FILE", "jfr-file-analysis");

        private final String requestType;
        private final String analysisPath;

        JifaWebFileType(String requestType, String analysisPath) {
            this.requestType = requestType;
            this.analysisPath = analysisPath;
        }

        static JifaWebFileType fromArtifact(JifaArtifactType type) {
            return switch (type) {
                case HPROF -> HEAP_DUMP;
                case GC_LOG -> GC_LOG;
                case THREAD_DUMP -> THREAD_DUMP;
                case JFR -> JFR_FILE;
            };
        }
    }

    private static final class ImportedIndex {
        int version;
        long updatedAtEpochMillis;
        Map<String, ImportedEntry> entries = new LinkedHashMap<>();
    }

    private static final class ImportedEntry {
        String sourcePath;
        String fingerprint;
        String requestType;
        String analysisPath;
        String remoteUniqueName;
        long remoteId;
        String displayName;
        long sourceSize;
        long sourceLastModifiedEpochMillis;
        long importedAtEpochMillis;
        long lastSeenAtEpochMillis;

        static ImportedEntry from(SourceFile source, RemoteFileView remote) {
            ImportedEntry entry = new ImportedEntry();
            entry.sourcePath = source.sourcePath.toString();
            entry.fingerprint = source.fingerprint;
            entry.requestType = source.fileType.requestType;
            entry.analysisPath = source.fileType.analysisPath;
            entry.remoteUniqueName = remote.uniqueName;
            entry.remoteId = remote.id;
            entry.displayName = source.displayName;
            entry.sourceSize = source.size;
            entry.sourceLastModifiedEpochMillis = source.lastModifiedEpochMillis;
            entry.importedAtEpochMillis = System.currentTimeMillis();
            entry.lastSeenAtEpochMillis = entry.importedAtEpochMillis;
            return entry;
        }

        boolean matches(SourceFile source) {
            return Objects.equals(sourcePath, source.sourcePath.toString())
                    && Objects.equals(fingerprint, source.fingerprint)
                    && Objects.equals(requestType, source.fileType.requestType);
        }
    }

    private static final class PersistedServerState {
        int port;
        long pid;
    }

    private static final class RemotePage<T> {
        int page;
        int pageSize;
        int totalSize;
        List<T> data;
    }

    private static final class RemoteFileView {
        long id;
        String uniqueName;
        String originalName;
        String type;
        long size;
        String createdTime;
    }
}
