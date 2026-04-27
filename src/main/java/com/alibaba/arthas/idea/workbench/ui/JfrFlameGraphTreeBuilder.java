package com.alibaba.arthas.idea.workbench.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jifa.jfr.vo.FlameGraph;

/**
 * 把 Jifa JFR flame graph 的线性栈数据整理成 IDEA 更适合展示的树结构。
 */
final class JfrFlameGraphTreeBuilder {

    private JfrFlameGraphTreeBuilder() {}

    static FlameGraphNode build(FlameGraph flameGraph) {
        return build(rowsOf(flameGraph));
    }

    static FlameGraphNode build(Collection<FlameGraphRow> rows) {
        FlameGraphNode root = new FlameGraphNode("All Threads", "All Threads");
        if (rows == null) {
            return root;
        }
        for (FlameGraphRow row : rows) {
            if (row == null || row.frames().isEmpty() || row.weight() <= 0) {
                continue;
            }
            root.addWeight(row.weight());
            FlameGraphNode current = root;
            for (String frame : row.frames()) {
                current = current.child(frame, packageName(frame));
                current.addWeight(row.weight());
            }
            current.addSelfWeight(row.weight());
        }
        return root;
    }

    static List<FlameGraphRow> rowsOf(FlameGraph flameGraph) {
        List<FlameGraphRow> rows = new ArrayList<>();
        if (flameGraph == null || flameGraph.getData() == null) {
            return rows;
        }
        Map<Integer, String> symbolTable = flameGraph.getSymbolTable() == null ? Map.of() : flameGraph.getSymbolTable();
        for (Object[] row : flameGraph.getData()) {
            if (row == null || row.length < 2) {
                continue;
            }
            List<String> frames = resolveFrames(row[0], symbolTable);
            if (frames.isEmpty()) {
                continue;
            }
            long weight = ((Number) row[1]).longValue();
            String taskName = row.length >= 3 && row[2] != null ? String.valueOf(row[2]) : "unknown";
            String leafMethod = frames.get(frames.size() - 1);
            rows.add(new FlameGraphRow(
                    frames, weight, taskName, packageName(leafMethod), ownerClassName(leafMethod), leafMethod));
        }
        return rows;
    }

    private static List<String> resolveFrames(Object frameContainer, Map<Integer, String> symbolTable) {
        List<String> frames = new ArrayList<>();
        if (frameContainer instanceof String[] ids) {
            for (String id : ids) {
                frames.add(resolveFrame(symbolTable, id));
            }
            return frames;
        }
        if (frameContainer instanceof Object[] ids) {
            for (Object id : ids) {
                frames.add(resolveFrame(symbolTable, String.valueOf(id)));
            }
            return frames;
        }
        if (frameContainer instanceof List<?> ids) {
            for (Object id : ids) {
                frames.add(resolveFrame(symbolTable, String.valueOf(id)));
            }
        }
        return frames;
    }

    private static String resolveFrame(Map<Integer, String> symbolTable, String frameId) {
        try {
            return symbolTable.getOrDefault(Integer.parseInt(frameId), frameId);
        } catch (NumberFormatException ignored) {
            return frameId;
        }
    }

    static String packageName(String frame) {
        String owner = ownerClassName(frame);
        if (owner.isBlank()) {
            return "";
        }
        int classSeparator = owner.lastIndexOf('.');
        return classSeparator <= 0 ? owner : owner.substring(0, classSeparator);
    }

    private static String ownerClassName(String frame) {
        if (frame == null || frame.isBlank()) {
            return "";
        }
        int methodSeparator = frame.lastIndexOf('.');
        if (methodSeparator <= 0) {
            return "";
        }
        return frame.substring(0, methodSeparator);
    }

    static final class FlameGraphNode {
        private final String name;
        private final String packageName;
        private long weight;
        private long selfWeight;
        private final Map<String, FlameGraphNode> children = new LinkedHashMap<>();

        FlameGraphNode(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }

        FlameGraphNode child(String childName, String childPackage) {
            return children.computeIfAbsent(childName, ignored -> new FlameGraphNode(childName, childPackage));
        }

        void addWeight(long delta) {
            weight += delta;
        }

        void addSelfWeight(long delta) {
            selfWeight += delta;
        }

        String getName() {
            return name;
        }

        String getPackageName() {
            return packageName;
        }

        long getWeight() {
            return weight;
        }

        long getSelfWeight() {
            return selfWeight;
        }

        Collection<FlameGraphNode> getChildren() {
            return children.values();
        }

        List<FlameGraphNode> sortedChildren() {
            List<FlameGraphNode> result = new ArrayList<>(children.values());
            result.sort((left, right) -> Long.compare(right.weight, left.weight));
            return result;
        }
    }

    record FlameGraphRow(
            List<String> frames,
            long weight,
            String taskName,
            String packageName,
            String className,
            String methodName) {}
}
