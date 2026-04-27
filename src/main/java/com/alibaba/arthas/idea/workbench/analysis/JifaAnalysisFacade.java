package com.alibaba.arthas.idea.workbench.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jifa.analysis.listener.ProgressListener;
import org.eclipse.jifa.common.domain.request.PagingRequest;
import org.eclipse.jifa.gclog.diagnoser.AnalysisConfig;
import org.eclipse.jifa.gclog.parser.GCLogAnalyzer;
import org.eclipse.jifa.gclog.parser.GCLogParserFactory;
import org.eclipse.jifa.hda.api.HeapDumpAnalyzer;
import org.eclipse.jifa.jfr.JFRAnalyzerImpl;
import org.eclipse.jifa.tda.ThreadDumpAnalyzer;

/**
 * IDEA 侧复用 Jifa 分析内核的门面。
 */
public final class JifaAnalysisFacade {

    private static final int CACHE_LIMIT = 8;
    private static final int HEADER_BYTES = 16 * 1024;
    private static final String JFR_HEADER = "FLR";
    private static final String HPROF_HEADER = "JAVA PROFILE 1.0.2";
    private static final String THREAD_DUMP_HEADER = "Full thread dump";
    private static final Map<CacheKey, JifaAnalysisResult> ANALYSIS_CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static volatile JifaHeapDumpExecutor heapDumpExecutor;

    private JifaAnalysisFacade() {}

    public static boolean isSupported(Path path) {
        try {
            return detect(path) != null;
        } catch (IOException exception) {
            return false;
        }
    }

    public static JifaArtifactDescriptor detect(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }

        String fileName =
                path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        byte[] header = readHeader(path);

        if (fileName.endsWith(".jfr") || isJfr(header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.JFR);
        }
        if (fileName.endsWith(".hprof") || fileName.endsWith(".phd") || isHprof(header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.HPROF);
        }
        if (isThreadDump(header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.THREAD_DUMP);
        }
        if (isLikelyGcLog(fileName, header) && isGcLog(path)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.GC_LOG);
        }
        return null;
    }

    public static JifaAnalysisResult analyze(JifaArtifactDescriptor descriptor, ProgressListener listener)
            throws Exception {
        return analyze(descriptor, listener, false);
    }

    public static JifaAnalysisResult analyze(
            JifaArtifactDescriptor descriptor, ProgressListener listener, boolean forceRefresh) throws Exception {
        ProgressListener effectiveListener = listener == null ? ProgressListener.NoOpProgressListener : listener;
        CacheKey cacheKey = cacheKey(descriptor.path());
        if (!forceRefresh) {
            JifaAnalysisResult cached = getCached(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        clearStaleEntries(descriptor.path());
        return switch (descriptor.type()) {
            case JFR -> cache(cacheKey, analyzeJfr(descriptor.path(), effectiveListener));
            case GC_LOG -> cache(cacheKey, analyzeGcLog(descriptor.path(), effectiveListener));
            case THREAD_DUMP -> cache(cacheKey, analyzeThreadDump(descriptor.path(), effectiveListener));
            case HPROF -> cache(cacheKey, analyzeHeapDump(descriptor.path(), effectiveListener));
        };
    }

    private static JifaJfrAnalysisResult analyzeJfr(Path path, ProgressListener listener) {
        JfrAnalysisCache cache = JfrAnalysisCache.defaultCache();
        try {
            JfrAnalysisCache.CachedSnapshot snapshot = cache.loadSnapshot(path);
            if (snapshot != null) {
                return new JifaJfrAnalysisResult(path, snapshot.metadata(), snapshot.result());
            }
        } catch (IOException ignored) {
            // 独立的 IDEA JFR 缓存不可用时，直接回退到实时分析。
        }

        JFRAnalyzerImpl analyzer = new JFRAnalyzerImpl(path, Map.of(), listener);
        JifaJfrAnalysisResult result =
                new JifaJfrAnalysisResult(path, analyzer, analyzer.metadata(), analyzer.getResult());
        try {
            cache.storeSnapshot(path, analyzer.metadata(), analyzer.getResult());
        } catch (IOException ignored) {
            // 缓存写入失败不影响分析结果。
        }
        return result;
    }

    private static JifaGcLogAnalysisResult analyzeGcLog(Path path, ProgressListener listener) throws Exception {
        GCLogAnalyzer analyzer = new GCLogAnalyzer(path.toFile(), listener);
        var model = analyzer.parse();
        AnalysisConfig config = AnalysisConfig.defaultConfig(model);
        return new JifaGcLogAnalysisResult(
                path,
                model,
                model.getGcModelMetadata(),
                config,
                model.getPauseStatistics(config.getTimeRange()),
                model.getMemoryStatistics(config.getTimeRange()),
                model.getPhaseStatistics(config.getTimeRange()),
                model.getGlobalAbnormalInfo(config));
    }

    private static JifaThreadDumpAnalysisResult analyzeThreadDump(Path path, ProgressListener listener)
            throws Exception {
        ThreadDumpAnalyzer analyzer = ThreadDumpAnalyzer.build(path, listener);
        return new JifaThreadDumpAnalysisResult(
                path,
                analyzer,
                analyzer.overview(),
                analyzer.threads(null, null, new PagingRequest(1, Integer.MAX_VALUE)));
    }

    private static JifaHprofAnalysisResult analyzeHeapDump(Path path, ProgressListener listener) throws Exception {
        try {
            HeapDumpAnalyzer analyzer = heapDumpExecutor().open(path, listener);
            return new JifaHprofAnalysisResult(path, analyzer, analyzer.getDetails());
        } catch (Exception exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new Exception(throwable);
        }
    }

    private static byte[] readHeader(Path path) throws IOException {
        byte[] buffer = new byte[HEADER_BYTES];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int read = inputStream.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            byte[] result = new byte[read];
            System.arraycopy(buffer, 0, result, 0, read);
            return result;
        }
    }

    private static boolean isJfr(byte[] header) {
        return header.length > JFR_HEADER.length()
                && new String(header, 0, JFR_HEADER.length(), StandardCharsets.UTF_8).equals(JFR_HEADER);
    }

    private static boolean isHprof(byte[] header) {
        return header.length > HPROF_HEADER.length()
                && new String(header, 0, HPROF_HEADER.length(), StandardCharsets.UTF_8).equals(HPROF_HEADER);
    }

    private static boolean isThreadDump(byte[] header) {
        return header.length > 20 + THREAD_DUMP_HEADER.length()
                && new String(header, 20, THREAD_DUMP_HEADER.length(), StandardCharsets.UTF_8)
                        .equals(THREAD_DUMP_HEADER);
    }

    private static boolean isGcLog(Path path) {
        GCLogParserFactory factory = new GCLogParserFactory();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            return factory.getParser(reader) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isLikelyGcLog(String fileName, byte[] header) {
        if (fileName.endsWith(".gclog")
                || fileName.endsWith(".gc")
                || fileName.endsWith(".log")
                || fileName.endsWith(".txt")) {
            return true;
        }
        int printable = 0;
        int inspected = Math.min(header.length, 256);
        for (int i = 0; i < inspected; i++) {
            byte value = header[i];
            if (value == '\n' || value == '\r' || value == '\t' || (value >= 32 && value < 127)) {
                printable++;
            }
        }
        return inspected > 0 && printable * 1.0 / inspected > 0.85;
    }

    private static CacheKey cacheKey(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        FileTime lastModifiedTime = Files.getLastModifiedTime(normalized);
        return new CacheKey(normalized, lastModifiedTime.toMillis(), Files.size(normalized));
    }

    private static JifaAnalysisResult getCached(CacheKey key) {
        synchronized (ANALYSIS_CACHE) {
            return ANALYSIS_CACHE.get(key);
        }
    }

    private static <T extends JifaAnalysisResult> T cache(CacheKey key, T result) {
        synchronized (ANALYSIS_CACHE) {
            JifaAnalysisResult previous = ANALYSIS_CACHE.put(key, result);
            if (previous != null && previous != result) {
                previous.dispose();
            }
            while (ANALYSIS_CACHE.size() > CACHE_LIMIT) {
                CacheKey eldestKey = ANALYSIS_CACHE.keySet().iterator().next();
                JifaAnalysisResult eldest = ANALYSIS_CACHE.remove(eldestKey);
                if (eldest != null) {
                    eldest.dispose();
                }
            }
        }
        return result;
    }

    private static void clearStaleEntries(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        synchronized (ANALYSIS_CACHE) {
            ANALYSIS_CACHE.entrySet().removeIf(entry -> {
                if (!entry.getKey().path().equals(normalized)) {
                    return false;
                }
                entry.getValue().dispose();
                return true;
            });
        }
    }

    private static JifaHeapDumpExecutor heapDumpExecutor() {
        JifaHeapDumpExecutor executor = heapDumpExecutor;
        if (executor != null) {
            return executor;
        }
        synchronized (JifaAnalysisFacade.class) {
            if (heapDumpExecutor == null) {
                heapDumpExecutor = new JifaHeapDumpExecutor();
            }
            return heapDumpExecutor;
        }
    }

    private record CacheKey(Path path, long lastModifiedMillis, long sizeBytes) {}
}
