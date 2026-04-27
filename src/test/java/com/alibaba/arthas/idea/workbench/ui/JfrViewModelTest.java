package com.alibaba.arthas.idea.workbench.ui;

import static org.junit.Assert.assertEquals;

import com.alibaba.arthas.idea.workbench.analysis.JifaJfrAnalysisResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jifa.jfr.enums.Unit;
import org.eclipse.jifa.jfr.model.PerfDimension;
import org.eclipse.jifa.jfr.vo.FlameGraph;
import org.junit.Test;

public class JfrViewModelTest {

    @Test
    public void shouldBuildFilteredSnapshotForThreadAndMethodViews() {
        FlameGraph flameGraph = new FlameGraph();
        flameGraph.setSymbolTable(Map.of(
                1, "com.demo.Root",
                2, "com.demo.Service.run",
                3, "com.demo.Dao.query",
                4, "com.demo.Api.call"));
        flameGraph.setData(new Object[][] {
            {new String[] {"1", "2", "3"}, 30L, "worker-1"},
            {new String[] {"1", "2", "4"}, 10L, "worker-2"},
            {new String[] {"1", "2", "3"}, 20L, "worker-2"},
        });

        JfrViewModel viewModel = JfrViewModel.of(
                PerfDimension.of("CPU Sample", "CPU Sample", new org.eclipse.jifa.jfr.model.Filter[0], Unit.COUNT),
                flameGraph,
                List.of(new JifaJfrAnalysisResult.TaskMetricRow("worker-1", 30L, 30L, "count=30", -1L, -1L)));

        JfrViewModel.Snapshot allThreadSnapshot = viewModel.snapshot(JfrViewModel.FilterMode.THREAD, Set.of(), "");
        JfrViewModel.Snapshot threadSnapshot =
                viewModel.snapshot(JfrViewModel.FilterMode.THREAD, Set.of("worker-2"), "");
        JfrViewModel.Snapshot packageSnapshot =
                viewModel.snapshot(JfrViewModel.FilterMode.PACKAGE, Set.of("com.demo"), "");
        JfrViewModel.Snapshot methodSnapshot =
                viewModel.snapshot(JfrViewModel.FilterMode.METHOD, Set.of("com.demo.Dao.query"), "");

        assertEquals(2, allThreadSnapshot.filterValues().size());
        assertEquals(30L, threadSnapshot.summary().totalWeight());
        assertEquals("worker-2", threadSnapshot.summary().hottestTask());
        assertEquals(1, threadSnapshot.filterValues().size());

        assertEquals(60L, packageSnapshot.summary().totalWeight());
        assertEquals("com.demo", packageSnapshot.summary().hottestPackage());
        assertEquals(1, packageSnapshot.filterValues().size());

        assertEquals(50L, methodSnapshot.summary().totalWeight());
        assertEquals("com.demo.Dao.query", methodSnapshot.summary().hottestMethod());
        assertEquals(1, methodSnapshot.filterValues().size());
        assertEquals("com.demo.Dao.query", methodSnapshot.filterValues().get(0).key());
    }
}
