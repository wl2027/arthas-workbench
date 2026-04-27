package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;
import org.eclipse.jifa.hda.api.HeapDumpAnalyzer;
import org.eclipse.jifa.hda.api.Model;

/**
 * HPROF/Heap Dump 分析结果。
 */
public final class JifaHprofAnalysisResult implements JifaAnalysisResult {

    private final Path path;
    private final HeapDumpAnalyzer analyzer;
    private final Model.Overview.Details details;

    public JifaHprofAnalysisResult(Path path, HeapDumpAnalyzer analyzer, Model.Overview.Details details) {
        this.path = path;
        this.analyzer = analyzer;
        this.details = details;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public JifaArtifactType getType() {
        return JifaArtifactType.HPROF;
    }

    public HeapDumpAnalyzer getAnalyzer() {
        return analyzer;
    }

    public Model.Overview.Details getDetails() {
        return details;
    }

    @Override
    public void dispose() {
        analyzer.dispose();
    }
}
