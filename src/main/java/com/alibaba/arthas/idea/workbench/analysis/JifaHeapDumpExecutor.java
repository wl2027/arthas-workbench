package com.alibaba.arthas.idea.workbench.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.eclipse.jifa.analysis.listener.ProgressListener;
import org.eclipse.jifa.hda.api.HeapDumpAnalyzer;
import org.eclipse.jifa.hda.api.Model;

/**
 * 通过隔离 JVM 进程承载 Jifa Heap Dump 分析，规避 IDEA PathClassLoader 与 OSGi/MAT 运行时冲突。
 */
final class JifaHeapDumpExecutor {

    HeapDumpAnalyzer open(Path path, ProgressListener listener) {
        try {
            return RemoteHeapDumpAnalyzer.open(path, listener);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to launch Jifa heap-dump helper process", exception);
        }
    }

    private static final class RemoteHeapDumpAnalyzer implements InvocationHandler {

        private static final Gson GSON = new GsonBuilder()
                .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
                .registerTypeAdapter(Model.OQLResult.class, new OqlResultTypeAdapter())
                .registerTypeAdapter(Model.CalciteSQLResult.class, new CalciteSqlResultTypeAdapter())
                .serializeNulls()
                .create();

        private final Path targetPath;
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final StringBuilder stderrBuffer = new StringBuilder();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object monitor = new Object();

        private RemoteHeapDumpAnalyzer(Path targetPath, Process process, BufferedWriter writer, BufferedReader reader) {
            this.targetPath = targetPath;
            this.process = process;
            this.writer = writer;
            this.reader = reader;
        }

        private static HeapDumpAnalyzer open(Path path, ProgressListener listener) throws IOException {
            List<String> command = buildCommand(path);
            Process process = new ProcessBuilder(command).start();
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            RemoteHeapDumpAnalyzer remote =
                    new RemoteHeapDumpAnalyzer(path.toAbsolutePath().normalize(), process, writer, reader);
            remote.captureStderr();
            remote.awaitReady(listener == null ? ProgressListener.NoOpProgressListener : listener);
            return (HeapDumpAnalyzer) Proxy.newProxyInstance(
                    HeapDumpAnalyzer.class.getClassLoader(), new Class<?>[] {HeapDumpAnalyzer.class}, remote);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "RemoteHeapDumpAnalyzer[" + targetPath + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }

            if ("dispose".equals(method.getName())) {
                request(method, args);
                shutdown();
                return null;
            }
            return request(method, args);
        }

        private Object request(Method method, Object[] args) {
            ensureOpen();
            synchronized (monitor) {
                try {
                    Request request = new Request(
                            method.getName(), GSON.toJsonTree(args == null ? List.of() : Arrays.asList(args)));
                    writer.write(GSON.toJson(request));
                    writer.newLine();
                    writer.flush();
                    while (true) {
                        JsonObject message = readMessage();
                        String kind = stringField(message, "kind");
                        if ("response".equals(kind)) {
                            if (!message.get("ok").getAsBoolean()) {
                                throw remoteFailure(message);
                            }
                            return deserializeResult(message.get("result"), method.getGenericReturnType());
                        }
                        if ("progress".equals(kind)) {
                            continue;
                        }
                        if ("ready".equals(kind)) {
                            continue;
                        }
                        throw new IllegalStateException("Unexpected helper message: " + message);
                    }
                } catch (IOException exception) {
                    shutdown();
                    throw new IllegalStateException("Heap dump helper process failed", exception);
                }
            }
        }

        private void awaitReady(ProgressListener listener) throws IOException {
            synchronized (monitor) {
                while (true) {
                    JsonObject message = readMessage();
                    String kind = stringField(message, "kind");
                    if ("progress".equals(kind)) {
                        relayProgress(listener, message);
                        continue;
                    }
                    if ("ready".equals(kind)) {
                        listener.subTask("Jifa heap dump helper ready");
                        return;
                    }
                    if ("response".equals(kind) && !message.get("ok").getAsBoolean()) {
                        throw remoteFailure(message);
                    }
                    throw new IllegalStateException("Unexpected helper startup message: " + message);
                }
            }
        }

        private JsonObject readMessage() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Helper process exited unexpectedly. stderr: " + stderrTail());
            }
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) {
                throw new IOException("Invalid helper response: " + line);
            }
            return element.getAsJsonObject();
        }

        private void relayProgress(ProgressListener listener, JsonObject message) {
            String action = stringField(message, "action");
            switch (action) {
                case "beginTask" -> listener.beginTask(stringField(message, "name"), intField(message, "workload"));
                case "subTask" -> listener.subTask(stringField(message, "name"));
                case "worked" -> listener.worked(intField(message, "workload"));
                case "message" ->
                    listener.sendUserMessage(
                            ProgressListener.Level.valueOf(stringField(message, "level")),
                            stringField(message, "message"),
                            null);
                default ->
                    listener.sendUserMessage(
                            ProgressListener.Level.INFO, "Unknown helper progress action: " + action, null);
            }
        }

        private RuntimeException remoteFailure(JsonObject message) {
            String errorType = stringField(message, "errorType");
            String errorMessage = stringField(message, "errorMessage");
            String stackTrace = stringField(message, "stackTrace");
            String stderr = stderrTail();
            StringBuilder builder = new StringBuilder();
            builder.append(errorType).append(": ").append(errorMessage);
            if (!stackTrace.isBlank()) {
                builder.append(System.lineSeparator()).append(stackTrace);
            }
            if (!stderr.isBlank()) {
                builder.append(System.lineSeparator())
                        .append("--- helper stderr ---")
                        .append(System.lineSeparator())
                        .append(stderr);
            }
            return new IllegalStateException(builder.toString());
        }

        private Object deserializeResult(JsonElement result, Type genericReturnType) {
            if (genericReturnType == void.class || genericReturnType == Void.class) {
                return null;
            }
            Type targetType = containsWildcard(genericReturnType)
                    ? TypeToken.get(methodRawType(genericReturnType)).getType()
                    : genericReturnType;
            return GSON.fromJson(result, targetType);
        }

        private boolean containsWildcard(Type type) {
            return type.getTypeName().contains("?");
        }

        private Class<?> methodRawType(Type type) {
            return TypeToken.get(type).getRawType();
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Heap dump helper is already closed");
            }
        }

        private void shutdown() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                writer.close();
            } catch (IOException ignored) {
                // ignore close failure
            }
            try {
                reader.close();
            } catch (IOException ignored) {
                // ignore close failure
            }
            process.destroy();
        }

        private void captureStderr() {
            Thread thread = new Thread(
                    () -> {
                        try (BufferedReader stderrReader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = stderrReader.readLine()) != null) {
                                synchronized (stderrBuffer) {
                                    if (!stderrBuffer.isEmpty()) {
                                        stderrBuffer.append(System.lineSeparator());
                                    }
                                    stderrBuffer.append(line);
                                }
                            }
                        } catch (IOException ignored) {
                            // ignore helper stderr capture failure
                        }
                    },
                    "jifa-hprof-helper-stderr");
            thread.setDaemon(true);
            thread.start();
        }

        private String stderrTail() {
            synchronized (stderrBuffer) {
                return stderrBuffer.toString();
            }
        }
    }

    static final class JifaHeapDumpWorkerMain {

        private static final Gson GSON = new GsonBuilder()
                .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
                .registerTypeAdapter(Model.OQLResult.class, new OqlResultTypeAdapter())
                .registerTypeAdapter(Model.CalciteSQLResult.class, new CalciteSqlResultTypeAdapter())
                .serializeNulls()
                .create();

        private JifaHeapDumpWorkerMain() {}

        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                writeJson(new Response(
                        "response", false, null, "IllegalArgumentException", "Missing heap dump path", ""));
                System.exit(2);
                return;
            }
            Path heapDumpPath = Path.of(args[0]).toAbsolutePath().normalize();
            HeapDumpAnalyzer analyzer = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    BufferedWriter ignored =
                            new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
                analyzer = loadAnalyzer(heapDumpPath);
                writeJson(new Ready("ready"));
                String line;
                while ((line = reader.readLine()) != null) {
                    Request request = GSON.fromJson(line, Request.class);
                    if (request == null || request.method() == null) {
                        writeJson(new Response(
                                "response", false, null, "IllegalArgumentException", "Invalid request", ""));
                        continue;
                    }
                    try {
                        int argumentCount =
                                request.args() == null || request.args().isJsonNull()
                                        ? 0
                                        : request.args().getAsJsonArray().size();
                        Method method = findMethod(request.method(), argumentCount);
                        Object[] resolvedArgs = resolveArguments(method, request.args());
                        Object value = method.invoke(analyzer, resolvedArgs);
                        writeJson(new Response(
                                "response",
                                true,
                                GSON.toJsonTree(value, method.getGenericReturnType()),
                                null,
                                null,
                                null));
                        if ("dispose".equals(method.getName())) {
                            break;
                        }
                    } catch (Throwable throwable) {
                        Throwable cause = rootCause(throwable);
                        writeJson(new Response(
                                "response",
                                false,
                                null,
                                cause.getClass().getName(),
                                Objects.toString(cause.getMessage(), ""),
                                stackTrace(cause)));
                    }
                }
            } finally {
                if (analyzer != null) {
                    analyzer.dispose();
                }
            }
        }

        private static HeapDumpAnalyzer loadAnalyzer(Path heapDumpPath) {
            ProgressListener listener = new ProgressListener() {

                private final StringBuilder log = new StringBuilder();
                private int workload;
                private int worked;

                @Override
                public void beginTask(String name, int workload) {
                    this.workload = Math.max(workload, 0);
                    this.worked = 0;
                    append(name);
                    writeJson(new Progress("progress", "beginTask", name, workload, null, null));
                }

                @Override
                public void subTask(String name) {
                    append(name);
                    writeJson(new Progress("progress", "subTask", name, 0, null, null));
                }

                @Override
                public void worked(int workload) {
                    worked += workload;
                    writeJson(new Progress("progress", "worked", null, workload, null, null));
                }

                @Override
                public void sendUserMessage(Level level, String message, Throwable throwable) {
                    append(message);
                    writeJson(new Progress(
                            "progress",
                            "message",
                            null,
                            0,
                            level == null ? ProgressListener.Level.INFO.name() : level.name(),
                            throwable == null ? message : message + " - " + throwable));
                }

                @Override
                public String log() {
                    return log.toString();
                }

                @Override
                public double percent() {
                    if (workload <= 0) {
                        return 0.0;
                    }
                    return Math.min(1.0, (double) worked / workload);
                }

                private void append(String message) {
                    if (message == null || message.isBlank()) {
                        return;
                    }
                    if (!log.isEmpty()) {
                        log.append(System.lineSeparator());
                    }
                    log.append(message);
                }
            };
            try {
                Class<?> executorClass = org.eclipse.jifa.hdp.provider.HeapDumpAnalysisApiExecutor.class;
                Method buildAnalyzer = executorClass.getDeclaredMethod(
                        "buildAnalyzer", Path.class, java.util.Map.class, ProgressListener.class);
                buildAnalyzer.setAccessible(true);
                return (HeapDumpAnalyzer) buildAnalyzer.invoke(
                        executorClass.getDeclaredConstructor().newInstance(),
                        heapDumpPath,
                        java.util.Map.of(),
                        listener);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to build heap dump analyzer in helper process", exception);
            }
        }

        private static Method findMethod(String name, int argumentCount) throws NoSuchMethodException {
            for (Method method : HeapDumpAnalyzer.class.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == argumentCount) {
                    return method;
                }
            }
            throw new NoSuchMethodException(name + "/" + argumentCount);
        }

        private static Object[] resolveArguments(Method method, JsonElement args) {
            Type[] parameterTypes = method.getGenericParameterTypes();
            Object[] resolved = new Object[parameterTypes.length];
            var array = args == null || args.isJsonNull() ? new com.google.gson.JsonArray() : args.getAsJsonArray();
            for (int index = 0; index < parameterTypes.length; index++) {
                resolved[index] = GSON.fromJson(array.get(index), parameterTypes[index]);
            }
            return resolved;
        }

        private static Throwable rootCause(Throwable throwable) {
            Throwable current = throwable;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static String stackTrace(Throwable throwable) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(writer));
            return writer.toString();
        }

        private static void writeJson(Object message) {
            try {
                System.out.write(GSON.toJson(message).getBytes(StandardCharsets.UTF_8));
                System.out.write('\n');
                System.out.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write helper response", exception);
            }
        }
    }

    private static List<String> buildCommand(Path heapDumpPath) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-cp");
        command.add(buildHelperClasspath());
        command.add(JifaHeapDumpWorkerMain.class.getName());
        command.add(heapDumpPath.toAbsolutePath().normalize().toString());
        return command;
    }

    private static Path javaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(binary);
    }

    private static String buildHelperClasspath() throws IOException {
        String currentClasspath = System.getProperty("java.class.path", "");
        if (classpathContains(currentClasspath, JifaHeapDumpExecutor.class)) {
            return currentClasspath;
        }

        Path workerLocation = classLocation(JifaHeapDumpExecutor.class);
        if (Files.isRegularFile(workerLocation)) {
            Path libDirectory = workerLocation.getParent();
            try (var stream = Files.list(libDirectory)) {
                return stream.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .map(Path::toString)
                        .collect(Collectors.joining(java.io.File.pathSeparator));
            }
        }
        Set<String> entries = new LinkedHashSet<>();
        if (!currentClasspath.isBlank()) {
            entries.addAll(Arrays.asList(currentClasspath.split(java.io.File.pathSeparator)));
        }
        entries.add(workerLocation.toString());
        return entries.stream()
                .filter(entry -> !entry.isBlank())
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static Path classLocation(Class<?> type) {
        try {
            if (type.getProtectionDomain() != null
                    && type.getProtectionDomain().getCodeSource() != null
                    && type.getProtectionDomain().getCodeSource().getLocation() != null) {
                return Path.of(
                        type.getProtectionDomain().getCodeSource().getLocation().toURI());
            }

            String resourceName = type.getName().replace('.', '/') + ".class";
            URL resource = type.getClassLoader() == null
                    ? ClassLoader.getSystemResource(resourceName)
                    : type.getClassLoader().getResource(resourceName);
            if (resource == null) {
                throw new IllegalStateException("Missing class resource: " + resourceName);
            }

            String externalForm = resource.toExternalForm();
            if (externalForm.startsWith("jar:")) {
                int separator = externalForm.indexOf("!/");
                if (separator < 0) {
                    throw new IllegalStateException("Unexpected jar resource: " + externalForm);
                }
                return Path.of(new URL(externalForm.substring("jar:".length(), separator)).toURI());
            }

            Path classFile = Path.of(resource.toURI());
            Path root = classFile;
            for (String ignored : resourceName.split("/")) {
                root = root.getParent();
            }
            return root;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve code source for " + type.getName(), exception);
        }
    }

    private static boolean classpathContains(String classpath, Class<?> type) {
        if (classpath == null || classpath.isBlank()) {
            return false;
        }
        String classResource = type.getName().replace('.', '/') + ".class";
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry);
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(classResource))) {
                return true;
            }
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                try (var jar = new java.util.jar.JarFile(path.toFile())) {
                    if (jar.getEntry(classResource) != null) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // ignore broken classpath entry
                }
            }
        }
        return false;
    }

    private static String stringField(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int intField(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    private record Request(String method, JsonElement args) {}

    private record Progress(String kind, String action, String name, int workload, String level, String message) {}

    private record Ready(String kind) {}

    private record Response(
            String kind, boolean ok, JsonElement result, String errorType, String errorMessage, String stackTrace) {}

    private static final class PathTypeAdapter implements JsonSerializer<Path>, JsonDeserializer<Path> {

        @Override
        public JsonElement serialize(Path src, Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
            return src == null ? null : new JsonPrimitive(src.toString());
        }

        @Override
        public Path deserialize(JsonElement json, Type typeOfT, com.google.gson.JsonDeserializationContext context) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            return Path.of(json.getAsString());
        }
    }

    private static final class OqlResultTypeAdapter implements JsonDeserializer<Model.OQLResult> {

        @Override
        public Model.OQLResult deserialize(
                JsonElement json, Type typeOfT, com.google.gson.JsonDeserializationContext context) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            JsonObject object = json.getAsJsonObject();
            int type = object.get("type").getAsInt();
            return switch (type) {
                case Model.OQLResult.TABLE -> context.deserialize(json, Model.OQLResult.TableResult.class);
                case Model.OQLResult.TREE -> context.deserialize(json, Model.OQLResult.TreeResult.class);
                case Model.OQLResult.TEXT -> context.deserialize(json, Model.OQLResult.TextResult.class);
                default -> throw new IllegalStateException("Unsupported OQL result type: " + type);
            };
        }
    }

    private static final class CalciteSqlResultTypeAdapter implements JsonDeserializer<Model.CalciteSQLResult> {

        @Override
        public Model.CalciteSQLResult deserialize(
                JsonElement json, Type typeOfT, com.google.gson.JsonDeserializationContext context) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            JsonObject object = json.getAsJsonObject();
            int type = object.get("type").getAsInt();
            return switch (type) {
                case Model.CalciteSQLResult.TABLE ->
                    context.deserialize(json, Model.CalciteSQLResult.TableResult.class);
                case Model.CalciteSQLResult.TREE -> context.deserialize(json, Model.CalciteSQLResult.TreeResult.class);
                case Model.CalciteSQLResult.TEXT -> context.deserialize(json, Model.CalciteSQLResult.TextResult.class);
                default -> throw new IllegalStateException("Unsupported SQL result type: " + type);
            };
        }
    }
}
