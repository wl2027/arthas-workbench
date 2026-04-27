package com.alibaba.arthas.idea.workbench.analysis;

/**
 * 当前插件内支持的 Jifa 文件分析类型。
 */
public enum JifaArtifactType {
    JFR("JFR"),
    GC_LOG("GC Log"),
    THREAD_DUMP("Thread Dump"),
    HPROF("Heap Dump");

    private final String displayName;

    JifaArtifactType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
