package com.alibaba.arthas.idea.workbench.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.eclipse.jifa.jfr.vo.FlameGraph;
import org.junit.Test;

/**
 * Flame graph 树聚合测试。
 */
public class JfrFlameGraphTreeBuilderTest {

    @Test
    public void shouldAggregateFlameGraphStacksIntoWeightedTree() {
        FlameGraph flameGraph = new FlameGraph();
        flameGraph.setSymbolTable(Map.of(
                1, "com.demo.Root",
                2, "com.demo.Service.run",
                3, "com.demo.Dao.query",
                4, "java.base.Thread.sleep"));
        flameGraph.setData(new Object[][] {
            {new String[] {"1", "2", "3"}, 30L, "worker-1"},
            {new String[] {"1", "2", "4"}, 10L, "worker-1"},
            {new String[] {"1", "2", "3"}, 20L, "worker-2"},
        });

        JfrFlameGraphTreeBuilder.FlameGraphNode root = JfrFlameGraphTreeBuilder.build(flameGraph);

        assertEquals(60L, root.getWeight());
        JfrFlameGraphTreeBuilder.FlameGraphNode first = root.sortedChildren().get(0);
        assertEquals("com.demo.Root", first.getName());
        assertEquals(60L, first.getWeight());

        JfrFlameGraphTreeBuilder.FlameGraphNode service = first.sortedChildren().get(0);
        assertEquals("com.demo.Service.run", service.getName());
        assertEquals(60L, service.getWeight());

        List<JfrFlameGraphTreeBuilder.FlameGraphNode> leaves = service.sortedChildren();
        assertEquals("com.demo.Dao.query", leaves.get(0).getName());
        assertEquals(50L, leaves.get(0).getWeight());
        assertEquals(50L, leaves.get(0).getSelfWeight());
        assertEquals("java.base.Thread.sleep", leaves.get(1).getName());
        assertEquals(10L, leaves.get(1).getWeight());
        assertEquals("com.demo", leaves.get(0).getPackageName());
    }

    @Test
    public void shouldAcceptCachedFlameGraphRowsDeserializedAsLists() {
        FlameGraph flameGraph = new FlameGraph();
        flameGraph.setSymbolTable(Map.of(1, "com.demo.Service.run", 2, "com.demo.Dao.query"));
        flameGraph.setData(new Object[][] {
            {List.of("1", "2"), 12.0d, "worker-1"},
        });

        List<JfrFlameGraphTreeBuilder.FlameGraphRow> rows = JfrFlameGraphTreeBuilder.rowsOf(flameGraph);

        assertEquals(1, rows.size());
        assertEquals("worker-1", rows.get(0).taskName());
        assertEquals("com.demo", rows.get(0).packageName());
        assertEquals("com.demo.Dao.query", rows.get(0).methodName());
        assertEquals("com.demo.Dao", rows.get(0).className());
        assertTrue(rows.get(0).frames().contains("com.demo.Service.run"));
    }
}
