package com.alibaba.arthas.idea.workbench.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 轻量识别当前插件支持交给 Jifa Web 处理的 JVM 分析文件类型。
 */
public final class JifaAnalysisFacade {

    private static final int HEADER_BYTES = 16 * 1024;
    private static final String JFR_HEADER = "FLR";
    private static final String HPROF_HEADER = "JAVA PROFILE 1.0.2";
    private static final String THREAD_DUMP_HEADER = "Full thread dump";

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

        String fileName = fileNameOf(path);
        byte[] header = readHeader(path);
        String textHeader = new String(header, StandardCharsets.UTF_8);

        if (fileName.endsWith(".jfr") || isJfr(header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.JFR);
        }
        if (fileName.endsWith(".hprof") || fileName.endsWith(".phd") || isHprof(header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.HPROF);
        }
        if (isThreadDump(textHeader, header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.THREAD_DUMP);
        }
        if (isGcLog(fileName, textHeader, header)) {
            return new JifaArtifactDescriptor(path, JifaArtifactType.GC_LOG);
        }
        return null;
    }

    private static String fileNameOf(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
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
        return header.length >= JFR_HEADER.length()
                && new String(header, 0, JFR_HEADER.length(), StandardCharsets.UTF_8).equals(JFR_HEADER);
    }

    private static boolean isHprof(byte[] header) {
        return header.length >= HPROF_HEADER.length()
                && new String(header, 0, HPROF_HEADER.length(), StandardCharsets.UTF_8).equals(HPROF_HEADER);
    }

    private static boolean isThreadDump(String textHeader, byte[] header) {
        if (textHeader.contains(THREAD_DUMP_HEADER) || textHeader.contains("java.lang.Thread.State:")) {
            return true;
        }
        if (header.length > 20 + THREAD_DUMP_HEADER.length()) {
            return new String(header, 20, THREAD_DUMP_HEADER.length(), StandardCharsets.UTF_8)
                    .equals(THREAD_DUMP_HEADER);
        }
        return false;
    }

    private static boolean isGcLog(String fileName, String textHeader, byte[] header) {
        if (!isTextLike(header)) {
            return false;
        }
        if (textHeader.contains(THREAD_DUMP_HEADER) || textHeader.contains("java.lang.Thread.State:")) {
            return false;
        }
        if (fileName.endsWith(".gclog")) {
            return true;
        }
        if (!(fileName.endsWith(".gc") || fileName.endsWith(".log") || fileName.endsWith(".txt"))) {
            return false;
        }
        return textHeader.contains("[info][gc")
                || textHeader.contains(" GC(")
                || textHeader.contains("GC(")
                || textHeader.contains("Pause Young")
                || textHeader.contains("Pause Full")
                || textHeader.contains("concurrent mark")
                || textHeader.contains("PSYoungGen")
                || textHeader.contains("ParNew")
                || textHeader.contains("CMS")
                || textHeader.contains("G1 Evacuation Pause")
                || textHeader.contains("Shenandoah")
                || textHeader.contains("ZGC");
    }

    private static boolean isTextLike(byte[] header) {
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
}
