package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.analysis.JifaGcLogAnalysisResult;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.table.AbstractTableModel;
import org.eclipse.jifa.gclog.diagnoser.GlobalDiagnoser;
import org.eclipse.jifa.gclog.event.GCEvent;
import org.eclipse.jifa.gclog.model.GCEventType;
import org.eclipse.jifa.gclog.vo.MemoryStatistics;
import org.eclipse.jifa.gclog.vo.PhaseStatistics;

/**
 * GC Log 原生 IDEA 视图。
 */
final class GcLogAnalysisView extends JPanel {

    GcLogAnalysisView(JifaGcLogAnalysisResult result) {
        super(new BorderLayout(0, 8));
        add(createSummary(result), BorderLayout.NORTH);
        add(createTabs(result), BorderLayout.CENTER);
    }

    private JComponent createSummary(JifaGcLogAnalysisResult result) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        panel.add(new javax.swing.JLabel("Collector: " + result.getMetadata().getCollector()));
        panel.add(new javax.swing.JLabel("Style: " + result.getMetadata().getLogStyle()));
        panel.add(new javax.swing.JLabel("Duration: "
                + JifaUiFormatters.formatDurationMillis(
                        result.getMetadata().getEndTime() - result.getMetadata().getStartTime())));
        panel.add(new javax.swing.JLabel(
                "GC Events: " + result.getModel().getGcEvents().size()));
        panel.add(new javax.swing.JLabel(
                "All Events: " + result.getModel().getAllEvents().size()));
        return panel;
    }

    private JComponent createTabs(JifaGcLogAnalysisResult result) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Pause", wrapTable(new PauseTableModel(result)));
        tabs.addTab("Memory", wrapTable(new MemoryTableModel(result)));
        tabs.addTab("Phases", wrapTable(new PhaseTableModel(result)));
        tabs.addTab("Problems", createProblemView(result.getAbnormalInfo()));
        tabs.addTab("Events", wrapTable(new EventTableModel(result.getModel().getGcEvents())));
        return tabs;
    }

    private JComponent createProblemView(GlobalDiagnoser.GlobalAbnormalInfo abnormalInfo) {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        StringBuilder builder = new StringBuilder();
        if (abnormalInfo.getMostSeriousProblem() != null) {
            builder.append("Most Serious Problem").append(System.lineSeparator());
            builder.append("Problem: ")
                    .append(abnormalInfo.getMostSeriousProblem().getProblem().getName())
                    .append(System.lineSeparator());
            builder.append("Sites: ")
                    .append(abnormalInfo.getMostSeriousProblem().getSites())
                    .append(System.lineSeparator());
            builder.append("Suggestions:").append(System.lineSeparator());
            abnormalInfo.getMostSeriousProblem().getSuggestions().forEach(item -> builder.append("- ")
                    .append(item.getName())
                    .append(System.lineSeparator()));
            builder.append(System.lineSeparator());
        }
        builder.append("Serious Problems").append(System.lineSeparator());
        if (abnormalInfo.getSeriousProblems() != null) {
            for (Map.Entry<String, List<Double>> entry :
                    abnormalInfo.getSeriousProblems().entrySet()) {
                builder.append("- ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue().size())
                        .append(" hit(s)")
                        .append(System.lineSeparator());
            }
        }
        area.setText(builder.toString());
        area.setCaretPosition(0);
        return new JScrollPane(area);
    }

    private JComponent wrapTable(AbstractTableModel model) {
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        return new JScrollPane(table);
    }

    private static final class PauseTableModel extends AbstractTableModel {

        private final Object[][] rows;

        private PauseTableModel(JifaGcLogAnalysisResult result) {
            rows = new Object[][] {
                {
                    "Throughput",
                    JifaUiFormatters.formatPercent(result.getPauseStatistics().getThroughput())
                },
                {
                    "Average Pause",
                    JifaUiFormatters.formatDurationMillis(
                            result.getPauseStatistics().getPauseAvg())
                },
                {
                    "Median Pause",
                    JifaUiFormatters.formatDurationMillis(
                            result.getPauseStatistics().getPauseMedian())
                },
                {
                    "P99 Pause",
                    JifaUiFormatters.formatDurationMillis(
                            result.getPauseStatistics().getPauseP99())
                },
                {
                    "P999 Pause",
                    JifaUiFormatters.formatDurationMillis(
                            result.getPauseStatistics().getPauseP999())
                },
                {
                    "Max Pause",
                    JifaUiFormatters.formatDurationMillis(
                            result.getPauseStatistics().getPauseMax())
                },
            };
        }

        @Override
        public int getRowCount() {
            return rows.length;
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows[rowIndex][columnIndex];
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Metric" : "Value";
        }
    }

    private static final class MemoryTableModel extends AbstractTableModel {

        private final Object[][] rows;

        private MemoryTableModel(JifaGcLogAnalysisResult result) {
            List<Object[]> data = new ArrayList<>();
            addRow(data, "Young", result.getMemoryStatistics().getYoung());
            addRow(data, "Old", result.getMemoryStatistics().getOld());
            addRow(data, "Humongous", result.getMemoryStatistics().getHumongous());
            addRow(data, "Heap", result.getMemoryStatistics().getHeap());
            addRow(data, "Metaspace", result.getMemoryStatistics().getMetaspace());
            rows = data.toArray(new Object[0][]);
        }

        private void addRow(List<Object[]> rows, String name, MemoryStatistics.MemoryStatisticsItem item) {
            if (item == null) {
                return;
            }
            rows.add(new Object[] {
                name,
                JifaUiFormatters.formatBytes(item.getCapacityAvg()),
                JifaUiFormatters.formatBytes(item.getUsedMax()),
                JifaUiFormatters.formatBytes(item.getUsedAvgAfterFullGC()),
                JifaUiFormatters.formatBytes(item.getUsedAvgAfterOldGC()),
            });
        }

        @Override
        public int getRowCount() {
            return rows.length;
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows[rowIndex][columnIndex];
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Region";
                case 1 -> "Avg Capacity";
                case 2 -> "Max Used";
                case 3 -> "Avg Used After Full GC";
                default -> "Avg Used After Old GC";
            };
        }
    }

    private static final class PhaseTableModel extends AbstractTableModel {

        private final List<Object[]> rows = new ArrayList<>();

        private PhaseTableModel(JifaGcLogAnalysisResult result) {
            PhaseStatistics phaseStatistics = result.getPhaseStatistics();
            if (phaseStatistics == null || phaseStatistics.getParents() == null) {
                return;
            }
            for (PhaseStatistics.ParentStatisticsInfo parent : phaseStatistics.getParents()) {
                if (parent.getSelf() != null) {
                    addRow("Parent", parent.getSelf());
                }
                if (parent.getPhases() != null) {
                    parent.getPhases().forEach(item -> addRow("Phase", item));
                }
                if (parent.getCauses() != null) {
                    parent.getCauses().forEach(item -> addRow("Cause", item));
                }
            }
        }

        private void addRow(String kind, PhaseStatistics.PhaseStatisticItem item) {
            rows.add(new Object[] {
                kind,
                item.getName(),
                item.getCount(),
                JifaUiFormatters.formatDurationMillis(item.getIntervalAvg()),
                JifaUiFormatters.formatDurationMillis(item.getDurationAvg()),
                JifaUiFormatters.formatDurationMillis(item.getDurationMax()),
                JifaUiFormatters.formatDurationMillis(item.getDurationTotal()),
            });
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex)[columnIndex];
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Kind";
                case 1 -> "Name";
                case 2 -> "Count";
                case 3 -> "Avg Interval";
                case 4 -> "Avg Duration";
                case 5 -> "Max Duration";
                default -> "Total Duration";
            };
        }
    }

    private static final class EventTableModel extends AbstractTableModel {

        private final List<GCEvent> events;

        private EventTableModel(List<GCEvent> events) {
            this.events = events;
        }

        @Override
        public int getRowCount() {
            return events.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            GCEvent event = events.get(rowIndex);
            GCEventType type = event.getEventType();
            return switch (columnIndex) {
                case 0 -> JifaUiFormatters.formatDouble(event.getStartTime());
                case 1 -> JifaUiFormatters.formatDurationMillis(event.getDuration());
                case 2 -> type == null ? "-" : type.getName();
                case 3 -> event.getCause() == null ? "-" : event.getCause().toString();
                case 4 -> JifaUiFormatters.formatDurationMillis(event.getPause());
                case 5 -> JifaUiFormatters.formatBytes(event.getAllocation());
                default -> JifaUiFormatters.formatBytes(event.getReclamation());
            };
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Start";
                case 1 -> "Duration";
                case 2 -> "Type";
                case 3 -> "Cause";
                case 4 -> "Pause";
                case 5 -> "Allocation";
                default -> "Reclamation";
            };
        }
    }
}
