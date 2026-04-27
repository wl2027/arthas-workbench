package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.analysis.JifaJfrAnalysisResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.jifa.jfr.model.PerfDimension;
import org.eclipse.jifa.jfr.vo.FlameGraph;

/**
 * 把 JFR 结果和 flame graph 行数据整理成更适合 IDEA UI 消费的只读模型。
 */
final class JfrViewModel {

    private final PerfDimension dimension;
    private final List<JfrFlameGraphTreeBuilder.FlameGraphRow> rows;
    private final List<JifaJfrAnalysisResult.TaskMetricRow> taskMetrics;

    private JfrViewModel(
            PerfDimension dimension,
            List<JfrFlameGraphTreeBuilder.FlameGraphRow> rows,
            List<JifaJfrAnalysisResult.TaskMetricRow> taskMetrics) {
        this.dimension = dimension;
        this.rows = rows;
        this.taskMetrics = taskMetrics;
    }

    static JfrViewModel of(
            PerfDimension dimension, FlameGraph flameGraph, List<JifaJfrAnalysisResult.TaskMetricRow> taskMetrics) {
        return new JfrViewModel(
                dimension, JfrFlameGraphTreeBuilder.rowsOf(flameGraph), taskMetrics == null ? List.of() : taskMetrics);
    }

    Snapshot snapshot(FilterMode filterMode, Set<String> selectedKeys, String searchText) {
        List<JfrFlameGraphTreeBuilder.FlameGraphRow> filteredRows = filteredRows(filterMode, selectedKeys, searchText);
        JfrFlameGraphTreeBuilder.FlameGraphNode tree = JfrFlameGraphTreeBuilder.build(filteredRows);

        Map<String, Long> taskWeights = aggregate(filteredRows, FilterMode.THREAD);
        Map<String, Long> packageWeights = aggregate(filteredRows, FilterMode.PACKAGE);
        Map<String, Long> classWeights = aggregate(filteredRows, FilterMode.CLASS);
        Map<String, Long> methodWeights = aggregate(filteredRows, FilterMode.METHOD);

        Summary summary = new Summary(
                filteredRows.stream()
                        .mapToLong(JfrFlameGraphTreeBuilder.FlameGraphRow::weight)
                        .sum(),
                filteredRows.size(),
                taskWeights.size(),
                packageWeights.size(),
                firstKey(taskWeights),
                firstKey(packageWeights),
                firstKey(classWeights),
                firstKey(methodWeights));

        return new Snapshot(
                tree,
                summary,
                toFilterValues(aggregate(filteredRows, filterMode)),
                toHotspotRows(taskWeights),
                toHotspotRows(packageWeights),
                toHotspotRows(classWeights),
                toHotspotRows(methodWeights));
    }

    Collection<FilterMode> availableFilterModes() {
        return List.of(FilterMode.THREAD, FilterMode.PACKAGE, FilterMode.CLASS, FilterMode.METHOD);
    }

    List<JifaJfrAnalysisResult.TaskMetricRow> taskMetrics() {
        return taskMetrics;
    }

    PerfDimension dimension() {
        return dimension;
    }

    private List<JfrFlameGraphTreeBuilder.FlameGraphRow> filteredRows(
            FilterMode filterMode, Set<String> selectedKeys, String searchText) {
        Set<String> normalizedKeys = selectedKeys == null ? Set.of() : new LinkedHashSet<>(selectedKeys);
        String normalizedSearch = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        List<JfrFlameGraphTreeBuilder.FlameGraphRow> filtered = new ArrayList<>();
        for (JfrFlameGraphTreeBuilder.FlameGraphRow row : rows) {
            String value = filterMode.valueOf(row);
            boolean searchMatched =
                    normalizedSearch.isBlank() || value.toLowerCase(Locale.ROOT).contains(normalizedSearch);
            boolean selected = normalizedKeys.isEmpty() || normalizedKeys.contains(value);
            if (searchMatched && selected) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private Map<String, Long> aggregate(List<JfrFlameGraphTreeBuilder.FlameGraphRow> filteredRows, FilterMode mode) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (JfrFlameGraphTreeBuilder.FlameGraphRow row : filteredRows) {
            values.merge(mode.valueOf(row), row.weight(), Long::sum);
        }
        return sortDescending(values);
    }

    private static Map<String, Long> sortDescending(Map<String, Long> values) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(values.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        Map<String, Long> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    private List<FilterValue> toFilterValues(Map<String, Long> values) {
        long total = values.values().stream().mapToLong(Long::longValue).sum();
        List<FilterValue> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            double share = total <= 0 ? 0.0 : (entry.getValue() * 100.0 / total);
            rows.add(new FilterValue(entry.getKey(), entry.getValue(), share));
        }
        return rows;
    }

    private List<HotspotRow> toHotspotRows(Map<String, Long> values) {
        long total = values.values().stream().mapToLong(Long::longValue).sum();
        List<HotspotRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            double share = total <= 0 ? 0.0 : (entry.getValue() * 100.0 / total);
            rows.add(new HotspotRow(entry.getKey(), entry.getValue(), share));
        }
        return rows;
    }

    private static String firstKey(Map<String, Long> values) {
        return values.isEmpty() ? "-" : values.keySet().iterator().next();
    }

    enum FilterMode {
        THREAD("Thread") {
            @Override
            String valueOf(JfrFlameGraphTreeBuilder.FlameGraphRow row) {
                return row.taskName();
            }
        },
        PACKAGE("Package") {
            @Override
            String valueOf(JfrFlameGraphTreeBuilder.FlameGraphRow row) {
                return row.packageName().isBlank() ? "<default>" : row.packageName();
            }
        },
        CLASS("Class") {
            @Override
            String valueOf(JfrFlameGraphTreeBuilder.FlameGraphRow row) {
                return row.className();
            }
        },
        METHOD("Method") {
            @Override
            String valueOf(JfrFlameGraphTreeBuilder.FlameGraphRow row) {
                return row.methodName();
            }
        };

        private final String label;

        FilterMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        abstract String valueOf(JfrFlameGraphTreeBuilder.FlameGraphRow row);
    }

    record Snapshot(
            JfrFlameGraphTreeBuilder.FlameGraphNode tree,
            Summary summary,
            List<FilterValue> filterValues,
            List<HotspotRow> hottestTasks,
            List<HotspotRow> hottestPackages,
            List<HotspotRow> hottestClasses,
            List<HotspotRow> hottestMethods) {}

    record Summary(
            long totalWeight,
            int stackCount,
            int taskCount,
            int packageCount,
            String hottestTask,
            String hottestPackage,
            String hottestClass,
            String hottestMethod) {}

    record FilterValue(String key, long weight, double share) {}

    record HotspotRow(String name, long weight, double share) {}
}
