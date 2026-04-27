package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;
import org.eclipse.jifa.common.domain.vo.PageView;
import org.eclipse.jifa.tda.ThreadDumpAnalyzer;
import org.eclipse.jifa.tda.vo.Overview;
import org.eclipse.jifa.tda.vo.VThread;

/**
 * Thread Dump 分析结果。
 */
public final class JifaThreadDumpAnalysisResult implements JifaAnalysisResult {

    private final Path path;
    private final ThreadDumpAnalyzer analyzer;
    private final Overview overview;
    private final PageView<VThread> threads;

    public JifaThreadDumpAnalysisResult(
            Path path, ThreadDumpAnalyzer analyzer, Overview overview, PageView<VThread> threads) {
        this.path = path;
        this.analyzer = analyzer;
        this.overview = overview;
        this.threads = threads;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public JifaArtifactType getType() {
        return JifaArtifactType.THREAD_DUMP;
    }

    public ThreadDumpAnalyzer getAnalyzer() {
        return analyzer;
    }

    public Overview getOverview() {
        return overview;
    }

    public PageView<VThread> getThreads() {
        return threads;
    }
}
