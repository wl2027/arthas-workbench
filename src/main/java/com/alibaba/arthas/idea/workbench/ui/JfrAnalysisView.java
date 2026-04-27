package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.analysis.JifaJfrAnalysisResult;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.eclipse.jifa.jfr.enums.Unit;
import org.eclipse.jifa.jfr.model.PerfDimension;

/**
 * 更完整的 IDEA 内嵌 JFR 分析视图。
 */
final class JfrAnalysisView extends JPanel {

    private static final Color HEADER_BACKGROUND = new Color(0xF7, 0xF1, 0xE7);
    private static final Color CARD_1 = new Color(0xE8, 0xF1, 0xE7);
    private static final Color CARD_2 = new Color(0xF6, 0xEA, 0xD7);
    private static final Color CARD_3 = new Color(0xE6, 0xF0, 0xF4);
    private static final Color CARD_4 = new Color(0xF3, 0xE6, 0xD8);

    private final JifaJfrAnalysisResult result;
    private final JComboBox<PerfDimension> dimensionCombo;
    private final JComboBox<JfrViewModel.FilterMode> filterModeCombo =
            new JComboBox<>(JfrViewModel.FilterMode.values());
    private final JBTextField filterSearchField = new JBTextField();
    private final JBCheckBox selectAllCheckBox = new JBCheckBox("全部");
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBLabel selectedFrameLabel = new JBLabel(" ");
    private final JfrFlameGraphPanel flameGraphPanel = new JfrFlameGraphPanel(this::showSelectedFrame);
    private final FilterTableModel filterTableModel = new FilterTableModel();
    private final TaskMetricTableModel taskMetricTableModel = new TaskMetricTableModel();
    private final HotspotTableModel hotTaskTableModel = new HotspotTableModel("线程");
    private final HotspotTableModel hotPackageTableModel = new HotspotTableModel("包");
    private final HotspotTableModel hotClassTableModel = new HotspotTableModel("类");
    private final HotspotTableModel hotMethodTableModel = new HotspotTableModel("方法");
    private final DimensionOverviewTableModel dimensionOverviewTableModel = new DimensionOverviewTableModel();
    private final ProblemTableModel problemTableModel = new ProblemTableModel();
    private final MetricCard totalCard = new MetricCard("Total", CARD_1);
    private final MetricCard taskCard = new MetricCard("Hottest Thread", CARD_2);
    private final MetricCard classCard = new MetricCard("Hottest Package", CARD_3);
    private final MetricCard methodCard = new MetricCard("Hottest Method", CARD_4);
    private final AtomicInteger requestCounter = new AtomicInteger();

    private JfrViewModel currentModel;
    private PerfDimension currentDimension;
    private boolean updatingFilters;

    JfrAnalysisView(JifaJfrAnalysisResult result) {
        super(new BorderLayout(0, 10));
        this.result = result;
        this.dimensionCombo = new JComboBox<>(result.getMetadata().getPerfDimensions());

        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(createHeader(), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);

        problemTableModel.update(result);
        dimensionOverviewTableModel.update(result.dimensionOverview());
        if (dimensionCombo.getItemCount() > 0) {
            PerfDimension preferredDimension = result.preferredDimension();
            if (preferredDimension != null) {
                dimensionCombo.setSelectedItem(preferredDimension);
            }
            loadDimension((PerfDimension) dimensionCombo.getSelectedItem());
        }
    }

    private JComponent createHeader() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(HEADER_BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD9, 0xC9, 0xB2)),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        JBLabel title = new JBLabel("Jifa JFR Analysis");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));

        JBLabel subtitle = new JBLabel("在 IDEA 中查看 JFR 维度总览、线程/包/类/方法热点、火焰图和高层问题诊断");
        subtitle.setForeground(new Color(0x5F, 0x52, 0x45));

        JPanel titlePanel = new JPanel(new BorderLayout(0, 4));
        titlePanel.setOpaque(false);
        titlePanel.add(title, BorderLayout.NORTH);
        titlePanel.add(subtitle, BorderLayout.CENTER);
        titlePanel.add(statusLabel, BorderLayout.SOUTH);

        dimensionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PerfDimension dimension) {
                    String unit = dimension.getUnit() == null
                            ? "-"
                            : dimension.getUnit().toString();
                    setText(dimension.getKey() + "  [" + unit + "]");
                }
                return this;
            }
        });
        dimensionCombo.addActionListener(event -> loadDimension((PerfDimension) dimensionCombo.getSelectedItem()));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        controls.add(new JBLabel("维度", SwingConstants.RIGHT));
        controls.add(dimensionCombo);

        JPanel cards = new JPanel(new GridLayout(1, 4, 10, 0));
        cards.setOpaque(false);
        cards.add(totalCard);
        cards.add(taskCard);
        cards.add(classCard);
        cards.add(methodCard);

        panel.add(titlePanel, BorderLayout.WEST);
        panel.add(controls, BorderLayout.EAST);
        panel.add(cards, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createBody() {
        OnePixelSplitter horizontal = new OnePixelSplitter(false, 0.76f);
        horizontal.setFirstComponent(createMainPane());
        horizontal.setSecondComponent(createInspectorPane());
        horizontal.setHonorComponentsMinimumSize(true);
        return horizontal;
    }

    private JComponent createMainPane() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

        selectedFrameLabel.setVerticalAlignment(SwingConstants.TOP);
        selectedFrameLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD8, 0xD3, 0xCB)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        selectedFrameLabel.setOpaque(true);
        selectedFrameLabel.setBackground(new Color(0xFF, 0xFB, 0xF5));

        flameGraphPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xDE, 0xD5, 0xC7)),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        JBScrollPane flameScroll = new JBScrollPane(flameGraphPanel);
        flameScroll.setPreferredSize(new Dimension(760, 420));
        flameScroll.setMinimumSize(new Dimension(360, 280));
        flameScroll.setBorder(BorderFactory.createEmptyBorder());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Task Metrics", createTaskMetricsPane());
        tabs.addTab("Top Threads", createHotspotPane(hotTaskTableModel));
        tabs.addTab("Top Packages", createHotspotPane(hotPackageTableModel));
        tabs.addTab("Top Classes", createHotspotPane(hotClassTableModel));
        tabs.addTab("Top Methods", createHotspotPane(hotMethodTableModel));
        tabs.addTab("Dimensions", createDimensionOverviewPane());
        tabs.addTab("Problems", createProblemPane());
        tabs.setPreferredSize(new Dimension(760, 250));
        tabs.setMinimumSize(new Dimension(360, 200));

        JPanel topPane = new JPanel(new BorderLayout(0, 8));
        topPane.setMinimumSize(new Dimension(360, 320));
        topPane.add(flameScroll, BorderLayout.CENTER);
        topPane.add(selectedFrameLabel, BorderLayout.SOUTH);

        panel.add(topPane, BorderLayout.CENTER);
        panel.add(tabs, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createInspectorPane() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD8, 0xD3, 0xCB)),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        JPanel controls = new JPanel(new BorderLayout(0, 8));
        JPanel modePanel = new JPanel(new BorderLayout(8, 0));
        modePanel.add(new JBLabel("过滤视角"), BorderLayout.WEST);
        filterModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof JfrViewModel.FilterMode mode) {
                    setText(mode.label());
                }
                return this;
            }
        });
        filterModeCombo.addActionListener(event -> refreshFiltersAndSnapshot());
        modePanel.add(filterModeCombo, BorderLayout.CENTER);

        filterSearchField.getEmptyText().setText("筛选当前视角，如线程名 / 包名 / 类名 / 方法名");
        filterSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refreshFiltersAndSnapshot();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refreshFiltersAndSnapshot();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refreshFiltersAndSnapshot();
            }
        });

        selectAllCheckBox.addActionListener(event -> {
            if (updatingFilters) {
                return;
            }
            filterTableModel.setAllSelected(selectAllCheckBox.isSelected());
            refreshSnapshot();
        });

        JPanel topActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topActions.add(selectAllCheckBox);

        controls.add(modePanel, BorderLayout.NORTH);
        controls.add(filterSearchField, BorderLayout.CENTER);
        controls.add(topActions, BorderLayout.SOUTH);

        JBTable filterTable = new JBTable(filterTableModel);
        filterTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        filterTable.getColumnModel().getColumn(0).setMaxWidth(56);
        filterTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JBCheckBox()));
        filterTable.setRowHeight(28);
        filterTableModel.setRefreshCallback(this::refreshSnapshot);

        panel.add(controls, BorderLayout.NORTH);
        panel.add(new JBScrollPane(filterTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createTaskMetricsPane() {
        JBTable table = new JBTable(taskMetricTableModel);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(26);
        return new JBScrollPane(table);
    }

    private JComponent createHotspotPane(HotspotTableModel model) {
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(26);
        return new JBScrollPane(table);
    }

    private JComponent createProblemPane() {
        JBTable table = new JBTable(problemTableModel);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(520);
        table.setRowHeight(28);
        return new JBScrollPane(table);
    }

    private JComponent createDimensionOverviewPane() {
        JBTable table = new JBTable(dimensionOverviewTableModel);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(26);
        return new JBScrollPane(table);
    }

    private void loadDimension(PerfDimension dimension) {
        if (dimension == null) {
            return;
        }
        int requestId = requestCounter.incrementAndGet();
        statusLabel.setText("正在加载 " + dimension.getKey() + " ...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JfrViewModel model =
                    JfrViewModel.of(dimension, result.flameGraph(dimension), result.taskMetrics(dimension));
            ApplicationManager.getApplication().invokeLater(() -> {
                if (requestId != requestCounter.get()) {
                    return;
                }
                currentDimension = dimension;
                currentModel = model;
                taskMetricTableModel.update(model.taskMetrics(), dimension.getUnit());
                refreshFiltersAndSnapshot();
            });
        });
    }

    private void refreshFiltersAndSnapshot() {
        if (currentModel == null) {
            return;
        }
        updatingFilters = true;
        try {
            List<JfrViewModel.FilterValue> values = currentModel
                    .snapshot(currentFilterMode(), Set.of(), filterSearchField.getText())
                    .filterValues();
            filterTableModel.replace(values, true);
            selectAllCheckBox.setSelected(!values.isEmpty());
        } finally {
            updatingFilters = false;
        }
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        if (currentModel == null || currentDimension == null) {
            return;
        }
        JfrViewModel.Snapshot snapshot = currentModel.snapshot(
                currentFilterMode(), filterTableModel.selectedKeys(), filterSearchField.getText());
        flameGraphPanel.setData(
                snapshot.tree(),
                currentDimension.getKey(),
                currentDimension.getUnit(),
                snapshot.summary().totalWeight());
        hotTaskTableModel.update(snapshot.hottestTasks(), currentDimension.getUnit());
        hotPackageTableModel.update(snapshot.hottestPackages(), currentDimension.getUnit());
        hotClassTableModel.update(snapshot.hottestClasses(), currentDimension.getUnit());
        hotMethodTableModel.update(snapshot.hottestMethods(), currentDimension.getUnit());
        totalCard.update(
                JifaUiFormatters.formatJfrValue(
                        currentDimension.getUnit(), snapshot.summary().totalWeight()),
                currentDimension.getKey());
        taskCard.update(snapshot.summary().hottestTask(), snapshot.summary().taskCount() + " threads");
        classCard.update(snapshot.summary().hottestPackage(), snapshot.summary().packageCount() + " packages");
        methodCard.update(snapshot.summary().hottestMethod(), snapshot.summary().stackCount() + " stacks");
        statusLabel.setText("已加载 " + currentDimension.getKey() + "，线程热点: "
                + snapshot.summary().hottestTask() + "，包热点: "
                + snapshot.summary().hottestPackage());
        selectedFrameLabel.setText(html(
                "维度",
                currentDimension.getKey(),
                "当前单位",
                currentDimension.getUnit() == null
                        ? "-"
                        : currentDimension.getUnit().toString(),
                "提示",
                "点击火焰图中的方法块可查看该节点的 total/self 占比"));
    }

    private void showSelectedFrame(JfrFlameGraphTreeBuilder.FlameGraphNode node) {
        if (node == null || currentDimension == null) {
            return;
        }
        long totalWeight = Math.max(1L, flameGraphPanelRootWeight());
        double totalPercent = node.getWeight() * 100.0 / totalWeight;
        double selfPercent = node.getSelfWeight() * 100.0 / totalWeight;
        selectedFrameLabel.setText(html(
                "方法",
                node.getName(),
                "包名",
                node.getPackageName().isBlank() ? "-" : node.getPackageName(),
                "Total / Self",
                JifaUiFormatters.formatJfrValue(currentDimension.getUnit(), node.getWeight())
                        + " / "
                        + JifaUiFormatters.formatJfrValue(currentDimension.getUnit(), node.getSelfWeight()),
                "占比",
                JifaUiFormatters.formatPercent(totalPercent) + " / " + JifaUiFormatters.formatPercent(selfPercent)));
    }

    private long flameGraphPanelRootWeight() {
        return currentModel == null || currentDimension == null
                ? 0L
                : currentModel
                        .snapshot(currentFilterMode(), filterTableModel.selectedKeys(), filterSearchField.getText())
                        .summary()
                        .totalWeight();
    }

    private JfrViewModel.FilterMode currentFilterMode() {
        JfrViewModel.FilterMode selected = (JfrViewModel.FilterMode) filterModeCombo.getSelectedItem();
        return selected == null ? JfrViewModel.FilterMode.THREAD : selected;
    }

    private String html(String... parts) {
        StringBuilder builder = new StringBuilder("<html>");
        for (int i = 0; i < parts.length; i += 2) {
            builder.append("<b>").append(parts[i]).append(":</b> ").append(parts[i + 1]);
            if (i + 2 < parts.length) {
                builder.append(" &nbsp;&nbsp; ");
            }
        }
        builder.append("</html>");
        return builder.toString();
    }

    private static final class MetricCard extends JPanel {

        private final JBLabel valueLabel = new JBLabel("-");
        private final JBLabel subtitleLabel = new JBLabel(" ");

        private MetricCard(String title, Color background) {
            super(new BorderLayout(0, 4));
            setOpaque(true);
            setBackground(background);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(background.darker()),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            JBLabel titleLabel = new JBLabel(title);
            titleLabel.setForeground(new Color(0x55, 0x47, 0x39));
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 15f));
            subtitleLabel.setForeground(new Color(0x68, 0x59, 0x49));
            add(titleLabel, BorderLayout.NORTH);
            add(valueLabel, BorderLayout.CENTER);
            add(subtitleLabel, BorderLayout.SOUTH);
        }

        void update(String value, String subtitle) {
            valueLabel.setText(value);
            subtitleLabel.setText(subtitle);
        }
    }

    private static final class FilterTableModel extends AbstractTableModel {

        private final List<Row> rows = new ArrayList<>();
        private Runnable refreshCallback = () -> {};

        void replace(List<JfrViewModel.FilterValue> values, boolean selected) {
            rows.clear();
            for (JfrViewModel.FilterValue value : values) {
                rows.add(new Row(selected, value.key(), value.weight(), value.share()));
            }
            fireTableDataChanged();
        }

        void setRefreshCallback(Runnable refreshCallback) {
            this.refreshCallback = refreshCallback;
        }

        void setAllSelected(boolean selected) {
            for (Row row : rows) {
                row.selected = selected;
            }
            fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
        }

        Set<String> selectedKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (Row row : rows) {
                if (row.selected) {
                    keys.add(row.key);
                }
            }
            return keys;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.selected;
                case 1 -> row.key;
                case 2 -> row.weight;
                case 3 -> JifaUiFormatters.formatPercent(row.share);
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && value instanceof Boolean selected) {
                rows.get(rowIndex).selected = selected;
                fireTableCellUpdated(rowIndex, columnIndex);
                refreshCallback.run();
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Use";
                case 1 -> "Value";
                case 2 -> "Weight";
                case 3 -> "Share";
                default -> "";
            };
        }

        private static final class Row {
            private boolean selected;
            private final String key;
            private final long weight;
            private final double share;

            private Row(boolean selected, String key, long weight, double share) {
                this.selected = selected;
                this.key = key;
                this.weight = weight;
                this.share = share;
            }
        }
    }

    private static final class TaskMetricTableModel extends AbstractTableModel {

        private final List<JifaJfrAnalysisResult.TaskMetricRow> rows = new ArrayList<>();
        private Unit unit = Unit.COUNT;

        void update(List<JifaJfrAnalysisResult.TaskMetricRow> rows, Unit unit) {
            this.rows.clear();
            this.rows.addAll(rows);
            this.unit = unit == null ? Unit.COUNT : unit;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JifaJfrAnalysisResult.TaskMetricRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.taskName();
                case 1 -> JifaUiFormatters.formatJfrValue(unit, row.value());
                case 2 -> row.sampleCount();
                case 3 -> row.detail();
                default -> "";
            };
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Task";
                case 1 -> "Metric";
                case 2 -> "Samples";
                case 3 -> "Detail";
                default -> "";
            };
        }
    }

    private static final class HotspotTableModel extends AbstractTableModel {

        private final List<JfrViewModel.HotspotRow> rows = new ArrayList<>();
        private final String nameColumnTitle;
        private Unit unit = Unit.COUNT;

        private HotspotTableModel(String nameColumnTitle) {
            this.nameColumnTitle = nameColumnTitle;
        }

        void update(List<JfrViewModel.HotspotRow> rows, Unit unit) {
            this.rows.clear();
            this.rows.addAll(rows);
            this.unit = unit == null ? Unit.COUNT : unit;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JfrViewModel.HotspotRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.name();
                case 1 -> JifaUiFormatters.formatJfrValue(unit, row.weight());
                case 2 -> JifaUiFormatters.formatPercent(row.share());
                default -> "";
            };
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> nameColumnTitle;
                case 1 -> "Weight";
                case 2 -> "Share";
                default -> "";
            };
        }
    }

    private static final class DimensionOverviewTableModel extends AbstractTableModel {

        private final List<JifaJfrAnalysisResult.DimensionOverviewRow> rows = new ArrayList<>();

        void update(List<JifaJfrAnalysisResult.DimensionOverviewRow> rows) {
            this.rows.clear();
            this.rows.addAll(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JifaJfrAnalysisResult.DimensionOverviewRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.dimensionKey();
                case 1 -> JifaUiFormatters.formatJfrValue(row.unit(), row.totalWeight());
                case 2 -> row.taskCount();
                case 3 -> row.hottestTask();
                default -> "";
            };
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Dimension";
                case 1 -> "Total";
                case 2 -> "Tasks";
                case 3 -> "Hottest Thread";
                default -> "";
            };
        }
    }

    private static final class ProblemTableModel extends AbstractTableModel {

        private final List<Row> rows = new ArrayList<>();

        void update(JifaJfrAnalysisResult result) {
            rows.clear();
            if (result.getResult().getProblems() != null) {
                result.getResult()
                        .getProblems()
                        .forEach(problem -> rows.add(new Row(problem.getSummary(), problem.getSolution())));
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return columnIndex == 0 ? row.summary() : row.solution();
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Problem" : "Suggestion";
        }

        private record Row(String summary, String solution) {}
    }
}
