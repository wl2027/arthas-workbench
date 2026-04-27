package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;
import org.eclipse.jifa.gclog.diagnoser.AnalysisConfig;
import org.eclipse.jifa.gclog.diagnoser.GlobalDiagnoser;
import org.eclipse.jifa.gclog.model.GCModel;
import org.eclipse.jifa.gclog.model.modeInfo.GCLogMetadata;
import org.eclipse.jifa.gclog.vo.MemoryStatistics;
import org.eclipse.jifa.gclog.vo.PauseStatistics;
import org.eclipse.jifa.gclog.vo.PhaseStatistics;

/**
 * GC 日志分析结果。
 */
public final class JifaGcLogAnalysisResult implements JifaAnalysisResult {

    private final Path path;
    private final GCModel model;
    private final GCLogMetadata metadata;
    private final AnalysisConfig analysisConfig;
    private final PauseStatistics pauseStatistics;
    private final MemoryStatistics memoryStatistics;
    private final PhaseStatistics phaseStatistics;
    private final GlobalDiagnoser.GlobalAbnormalInfo abnormalInfo;

    public JifaGcLogAnalysisResult(
            Path path,
            GCModel model,
            GCLogMetadata metadata,
            AnalysisConfig analysisConfig,
            PauseStatistics pauseStatistics,
            MemoryStatistics memoryStatistics,
            PhaseStatistics phaseStatistics,
            GlobalDiagnoser.GlobalAbnormalInfo abnormalInfo) {
        this.path = path;
        this.model = model;
        this.metadata = metadata;
        this.analysisConfig = analysisConfig;
        this.pauseStatistics = pauseStatistics;
        this.memoryStatistics = memoryStatistics;
        this.phaseStatistics = phaseStatistics;
        this.abnormalInfo = abnormalInfo;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public JifaArtifactType getType() {
        return JifaArtifactType.GC_LOG;
    }

    public GCModel getModel() {
        return model;
    }

    public GCLogMetadata getMetadata() {
        return metadata;
    }

    public AnalysisConfig getAnalysisConfig() {
        return analysisConfig;
    }

    public PauseStatistics getPauseStatistics() {
        return pauseStatistics;
    }

    public MemoryStatistics getMemoryStatistics() {
        return memoryStatistics;
    }

    public PhaseStatistics getPhaseStatistics() {
        return phaseStatistics;
    }

    public GlobalDiagnoser.GlobalAbnormalInfo getAbnormalInfo() {
        return abnormalInfo;
    }
}
