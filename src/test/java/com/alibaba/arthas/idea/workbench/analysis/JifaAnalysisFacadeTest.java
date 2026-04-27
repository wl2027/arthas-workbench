package com.alibaba.arthas.idea.workbench.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jifa.hda.api.Model;
import org.junit.Test;

/**
 * Jifa 分析门面的集成回归测试。
 */
public class JifaAnalysisFacadeTest {

    private static Path heapDump;

    @Test
    public void shouldDetectSupportedArtifactTypes() throws Exception {
        assertEquals(
                JifaArtifactType.JFR,
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/jfr/src/test/resources/jfr.jfr"))
                        .type());
        assertEquals(
                JifaArtifactType.GC_LOG,
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/gc-log/src/test/resources/17G1Parser.log"))
                        .type());
        assertEquals(
                JifaArtifactType.THREAD_DUMP,
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/thread-dump/src/test/resources/jstack_8.log"))
                        .type());
        Path heapDump = createHeapDump();
        assertEquals(JifaArtifactType.HPROF, JifaAnalysisFacade.detect(heapDump).type());
    }

    @Test
    public void shouldAnalyzeJfrFileWithEmbeddedJifa() throws Exception {
        JifaArtifactDescriptor descriptor =
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/jfr/src/test/resources/jfr.jfr"));
        JifaAnalysisResult result = JifaAnalysisFacade.analyze(descriptor, null);

        assertTrue(result instanceof JifaJfrAnalysisResult);
        JifaJfrAnalysisResult jfrResult = (JifaJfrAnalysisResult) result;
        assertTrue(jfrResult.getMetadata().getPerfDimensions().length > 0);
        assertNotNull(jfrResult.getResult());
    }

    @Test
    public void shouldAnalyzeGcLogWithEmbeddedJifa() throws Exception {
        JifaArtifactDescriptor descriptor =
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/gc-log/src/test/resources/17G1Parser.log"));
        JifaAnalysisResult result = JifaAnalysisFacade.analyze(descriptor, null);

        assertTrue(result instanceof JifaGcLogAnalysisResult);
        JifaGcLogAnalysisResult gcResult = (JifaGcLogAnalysisResult) result;
        assertEquals("G1 GC", gcResult.getMetadata().getCollector());
        assertTrue(gcResult.getModel().getGcEvents().size() > 0);
    }

    @Test
    public void shouldAnalyzeThreadDumpWithEmbeddedJifa() throws Exception {
        JifaArtifactDescriptor descriptor =
                JifaAnalysisFacade.detect(projectPath("jifa/analysis/thread-dump/src/test/resources/jstack_8.log"));
        JifaAnalysisResult result = JifaAnalysisFacade.analyze(descriptor, null);

        assertTrue(result instanceof JifaThreadDumpAnalysisResult);
        JifaThreadDumpAnalysisResult threadDumpResult = (JifaThreadDumpAnalysisResult) result;
        assertTrue(threadDumpResult.getThreads().getData().size() > 0);
        assertNotNull(threadDumpResult.getOverview());
    }

    @Test
    public void shouldAnalyzeHeapDumpWithEmbeddedJifa() throws Exception {
        Path heapDump = createHeapDump();
        JifaArtifactDescriptor descriptor = JifaAnalysisFacade.detect(heapDump);
        JifaAnalysisResult result = JifaAnalysisFacade.analyze(descriptor, null, true);

        assertTrue(result instanceof JifaHprofAnalysisResult);
        JifaHprofAnalysisResult heapDumpResult = (JifaHprofAnalysisResult) result;
        assertNotNull(heapDumpResult.getDetails());
        assertTrue(heapDumpResult.getDetails().getNumberOfObjects() > 0);
        List<Model.Thread.Item> threads = heapDumpResult
                .getAnalyzer()
                .getThreads("retainedHeap", false, "", org.eclipse.jifa.hda.api.SearchType.BY_NAME, 1, 50)
                .getData();
        assertNotNull(threads);
        Model.Thread.Item threadWithStack = threads.stream()
                .filter(Model.Thread.Item::isHasStack)
                .findFirst()
                .orElse(null);
        if (threadWithStack != null) {
            assertTrue(heapDumpResult
                            .getAnalyzer()
                            .getStackTrace(threadWithStack.getObjectId())
                            .size()
                    > 0);
        }
    }

    private Path projectPath(String relativePath) {
        return Path.of(relativePath).toAbsolutePath().normalize();
    }

    private Path createHeapDump() throws Exception {
        if (heapDump != null) {
            return heapDump;
        }
        Path directory = Files.createTempDirectory("arthas-workbench-hprof");
        heapDump = Files.createTempFile(directory, "sample", ".hprof").toAbsolutePath();
        Files.delete(heapDump);
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(heapDump.toString(), false);
        return heapDump;
    }
}
