package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;

/**
 * 统一描述一次要交给 Jifa 分析的本地文件。
 */
public record JifaArtifactDescriptor(Path path, JifaArtifactType type) {

    public String displayName() {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
