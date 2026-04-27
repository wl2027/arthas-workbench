package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.analysis.JifaThreadDumpAnalysisResult;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import org.eclipse.jifa.tda.vo.Overview;
import org.eclipse.jifa.tda.vo.VThread;

/**
 * Thread Dump 原生 IDEA 视图。
 */
final class ThreadDumpAnalysisView extends JPanel {

    private final JifaThreadDumpAnalysisResult result;
    private final JBTextArea rawContentArea = new JBTextArea();

    ThreadDumpAnalysisView(JifaThreadDumpAnalysisResult result) {
        super(new BorderLayout(0, 8));
        this.result = result;
        add(createSummary(result.getOverview()), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);
    }

    private JComponent createSummary(Overview overview) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        panel.add(new JLabel("VM: " + overview.getVmInfo()));
        panel.add(new JLabel("Timestamp: " + JifaUiFormatters.formatTimestampMillis(overview.getTimestamp())));
        panel.add(new JLabel("Threads: " + sum(overview.getThreadStat().getCounts())));
        panel.add(new JLabel("Java Threads: " + sum(overview.getJavaThreadStat().getJavaCounts())));
        panel.add(new JLabel("Daemon: " + overview.getJavaThreadStat().getDaemonCount()));
        panel.add(new JLabel("Deadlocks: " + overview.getDeadLockCount()));
        panel.add(new JLabel("Errors: " + overview.getErrorCount()));
        return panel;
    }

    private JComponent createBody() {
        ThreadTableModel model = new ThreadTableModel(result.getThreads().getData());
        JBTable threadTable = new JBTable(model);
        threadTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        threadTable.setAutoCreateRowSorter(true);
        threadTable.getSelectionModel().addListSelectionListener(event -> updateRawContent(event, threadTable));

        rawContentArea.setEditable(false);
        rawContentArea.setLineWrap(false);
        rawContentArea.setText("Select a thread to inspect its raw content.");

        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.32f);
        splitter.setFirstComponent(new JScrollPane(threadTable));
        splitter.setSecondComponent(new JScrollPane(rawContentArea));
        splitter.setHonorComponentsMinimumSize(true);
        return splitter;
    }

    private void updateRawContent(ListSelectionEvent event, JBTable table) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(selectedRow);
        VThread thread = ((ThreadTableModel) table.getModel()).row(modelRow);
        try {
            rawContentArea.setText(
                    String.join(System.lineSeparator(), result.getAnalyzer().rawContentOfThread(thread.getId())));
            rawContentArea.setCaretPosition(0);
        } catch (IOException exception) {
            rawContentArea.setText("Failed to load thread content: " + exception.getMessage());
        }
    }

    private int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static final class ThreadTableModel extends AbstractTableModel {

        private final List<VThread> rows;

        private ThreadTableModel(List<VThread> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
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
            VThread thread = rows.get(rowIndex);
            return columnIndex == 0 ? thread.getId() : thread.getName();
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Id" : "Thread Name";
        }

        private VThread row(int index) {
            return rows.get(index);
        }
    }
}
