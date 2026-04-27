package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;

/**
 * Jifa 分析结果的统一抽象。
 */
public interface JifaAnalysisResult {

    Path getPath();

    JifaArtifactType getType();

    default void dispose() {}
}
