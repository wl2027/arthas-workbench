package com.alibaba.arthas.idea.workbench.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * 轻量文件类型识别回归测试。
 */
public class JifaAnalysisFacadeTest {

    @Test
    public void shouldDetectSupportedArtifactTypes() throws Exception {
        Path jfr = createTempFile("sample", ".jfr", "FLR\0fake-jfr");
        Path hprof = createTempFile("sample", ".hprof", "JAVA PROFILE 1.0.2");
        Path threadDump = createTempFile("thread-dump", ".log", """
                        2026-04-27 20:00:00
                        Full thread dump OpenJDK 64-Bit Server VM:

                        "main" #1 prio=5 tid=0x1 nid=0x2 runnable
                           java.lang.Thread.State: RUNNABLE
                        """);
        Path gcLog = createTempFile("gc", ".log", """
                        [0.123s][info][gc] Using G1
                        [0.456s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 32M->8M(256M) 4.567ms
                        """);

        assertEquals(JifaArtifactType.JFR, JifaAnalysisFacade.detect(jfr).type());
        assertEquals(JifaArtifactType.HPROF, JifaAnalysisFacade.detect(hprof).type());
        assertEquals(
                JifaArtifactType.THREAD_DUMP,
                JifaAnalysisFacade.detect(threadDump).type());
        assertEquals(JifaArtifactType.GC_LOG, JifaAnalysisFacade.detect(gcLog).type());
        assertTrue(JifaAnalysisFacade.isSupported(gcLog));
    }

    @Test
    public void shouldRejectUnsupportedFiles() throws Exception {
        Path plainText = createTempFile("readme", ".txt", "hello world");
        assertNull(JifaAnalysisFacade.detect(plainText));
        assertTrue(!JifaAnalysisFacade.isSupported(plainText));
    }

    private Path createTempFile(String prefix, String suffix, String content) throws Exception {
        Path file = Files.createTempFile(prefix, suffix);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
