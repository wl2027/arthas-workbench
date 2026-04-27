package com.alibaba.arthas.idea.workbench.ui;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class HprofAnalysisViewTest {

    @Test
    public void shouldExposeAllFixedHeapDumpTabsFromJifaWeb() {
        assertEquals(
                List.of(
                        "Overview",
                        "Leak Suspects",
                        "Dominator Tree",
                        "Histogram",
                        "Threads",
                        "Class Loaders",
                        "Query",
                        "GC Roots",
                        "Direct Byte Buffers",
                        "Duplicate Classes",
                        "Unreachable Objects",
                        "System Properties",
                        "Env Variables"),
                HprofAnalysisView.requiredTabTitles());
    }
}
