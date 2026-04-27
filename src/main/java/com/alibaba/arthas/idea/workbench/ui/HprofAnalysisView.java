package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.analysis.JifaHprofAnalysisResult;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import org.eclipse.jifa.common.domain.vo.PageView;
import org.eclipse.jifa.hda.api.Model;
import org.eclipse.jifa.hda.api.SearchType;

/**
 * Heap Dump/HPROF 的 IDEA 原生视图，按 Jifa Web 固定页签组织能力，并额外提供 Inspector 侧栏。
 */
final class HprofAnalysisView extends JPanel {

    private static final List<String> REQUIRED_TAB_TITLES = List.of(
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
            "Env Variables");

    private final JifaHprofAnalysisResult result;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<TabKey, JPanel> tabHosts = new EnumMap<>(TabKey.class);
    private final EnumSet<TabKey> loadingTabs = EnumSet.noneOf(TabKey.class);
    private final EnumSet<TabKey> loadedTabs = EnumSet.noneOf(TabKey.class);

    private final JBLabel inspectorTitle = new JBLabel("Inspector");
    private final JBTextArea inspectorSummaryArea = createReadOnlyTextArea();
    private final JBTextArea inspectorValueArea = createReadOnlyTextArea();
    private final ListTableModel<Model.FieldView> inspectorFieldsModel =
            new ListTableModel<>(List.of(column("Name", field -> field.name), column("Value", field -> field.value)));
    private final ListTableModel<Model.FieldView> inspectorStaticFieldsModel =
            new ListTableModel<>(List.of(column("Name", field -> field.name), column("Value", field -> field.value)));

    HprofAnalysisView(JifaHprofAnalysisResult result) {
        super(new BorderLayout(0, 8));
        this.result = result;

        add(createSummary(result.getDetails()), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);

        ensureLoaded(TabKey.OVERVIEW);
    }

    static List<String> requiredTabTitles() {
        return REQUIRED_TAB_TITLES;
    }

    private JComponent createSummary(Model.Overview.Details details) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        panel.add(new javax.swing.JLabel("Used Heap: " + JifaUiFormatters.formatBytes(details.getUsedHeapSize())));
        panel.add(new javax.swing.JLabel("Objects: " + details.getNumberOfObjects()));
        panel.add(new javax.swing.JLabel("Classes: " + details.getNumberOfClasses()));
        panel.add(new javax.swing.JLabel("ClassLoaders: " + details.getNumberOfClassLoaders()));
        panel.add(new javax.swing.JLabel("GC Roots: " + details.getNumberOfGCRoots()));
        panel.add(new javax.swing.JLabel("Identifier Size: " + details.getIdentifierSize() + " B"));
        return panel;
    }

    private JComponent createBody() {
        for (TabKey key : TabKey.values()) {
            tabs.addTab(key.title, createAsyncHost(key));
        }
        tabs.addChangeListener(event -> ensureLoaded(TabKey.fromIndex(tabs.getSelectedIndex())));

        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.72f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(tabs);
        splitter.setSecondComponent(createInspectorPanel());
        return splitter;
    }

    private JComponent createInspectorPanel() {
        inspectorTitle.setHorizontalAlignment(SwingConstants.LEFT);

        JTabbedPane inspectorTabs = new JTabbedPane();
        inspectorTabs.addTab("Summary", new JScrollPane(inspectorSummaryArea));
        inspectorTabs.addTab("Fields", wrapTable(new JBTable(inspectorFieldsModel), true));
        inspectorTabs.addTab("Static Fields", wrapTable(new JBTable(inspectorStaticFieldsModel), true));
        inspectorTabs.addTab("Value", new JScrollPane(inspectorValueArea));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(inspectorTitle, BorderLayout.NORTH);
        panel.add(inspectorTabs, BorderLayout.CENTER);
        inspectorSummaryArea.setText("Select an object row to inspect its fields, static fields and resolved value.");
        return panel;
    }

    private JPanel createAsyncHost(TabKey key) {
        JPanel host = new JPanel(new BorderLayout());
        host.add(
                new JBLabel("Loading is deferred until this tab is opened.", SwingConstants.CENTER),
                BorderLayout.CENTER);
        tabHosts.put(key, host);
        return host;
    }

    private void ensureLoaded(TabKey key) {
        if (key == null || loadedTabs.contains(key) || loadingTabs.contains(key)) {
            return;
        }
        JPanel host = tabHosts.get(key);
        if (host == null) {
            return;
        }
        loadingTabs.add(key);
        host.removeAll();
        host.add(new JBLabel("Loading " + key.title + "...", SwingConstants.CENTER), BorderLayout.CENTER);
        host.revalidate();
        host.repaint();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                JComponent component = buildTabContent(key);
                ApplicationManager.getApplication().invokeLater(() -> {
                    loadingTabs.remove(key);
                    loadedTabs.add(key);
                    host.removeAll();
                    host.add(component, BorderLayout.CENTER);
                    host.revalidate();
                    host.repaint();
                });
            } catch (Throwable throwable) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    loadingTabs.remove(key);
                    host.removeAll();
                    host.add(wrapText("Failed to load " + key.title + ": " + throwable), BorderLayout.CENTER);
                    host.revalidate();
                    host.repaint();
                });
            }
        });
    }

    private JComponent buildTabContent(TabKey key) {
        return switch (key) {
            case OVERVIEW -> createOverviewView();
            case LEAK_SUSPECTS -> createLeakSuspectsView();
            case DOMINATOR_TREE -> createDominatorTreeView();
            case HISTOGRAM -> createHistogramView();
            case THREADS -> createThreadsView();
            case CLASS_LOADERS -> createClassLoadersView();
            case QUERY -> createQueryView();
            case GC_ROOTS -> createGcRootsView();
            case DIRECT_BYTE_BUFFERS -> createDirectByteBuffersView();
            case DUPLICATE_CLASSES -> createDuplicateClassesView();
            case UNREACHABLE_OBJECTS -> createUnreachableObjectsView();
            case SYSTEM_PROPERTIES -> createPropertiesView(result.getAnalyzer().getSystemProperties());
            case ENV_VARIABLES -> createPropertiesView(result.getAnalyzer().getEnvVariables());
        };
    }

    private JComponent createOverviewView() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(wrapText(buildOverviewText(result.getDetails())), BorderLayout.NORTH);
        ListTableModel<Model.Overview.BigObject> model = new ListTableModel<>(List.of(
                column("Label", Model.Overview.BigObject::getLabel),
                column("Object Id", Model.Overview.BigObject::getObjectId),
                column("Value", item -> String.format("%.2f%%", item.getValue() * 100)),
                column("Description", Model.Overview.BigObject::getDescription)));
        model.setRows(result.getAnalyzer().getBiggestObjects());
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.Overview.BigObject::getObjectId);
        installObjectActions(table, model, Model.Overview.BigObject::getObjectId, Model.Overview.BigObject::getLabel);
        panel.add(withHeader("Biggest Objects", wrapTable(table, true)), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createLeakSuspectsView() {
        Model.LeakReport leakReport = result.getAnalyzer().getLeakReport();
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(wrapText(buildLeakSummary(leakReport)), BorderLayout.NORTH);

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.38f);
        splitter.setHonorComponentsMinimumSize(true);

        ListTableModel<Model.LeakReport.Slice> slicesModel = new ListTableModel<>(List.of(
                column("Label", Model.LeakReport.Slice::getLabel),
                column("Object Id", Model.LeakReport.Slice::getObjectId),
                column("Value", slice -> String.format("%.2f%%", slice.getValue() * 100)),
                column("Description", Model.LeakReport.Slice::getDesc)));
        slicesModel.setRows(leakReport == null ? List.of() : leakReport.getSlices());
        JBTable slicesTable = new JBTable(slicesModel);
        slicesTable.setAutoCreateRowSorter(true);
        attachInspectorSelection(slicesTable, slicesModel, Model.LeakReport.Slice::getObjectId);
        installObjectActions(
                slicesTable, slicesModel, Model.LeakReport.Slice::getObjectId, Model.LeakReport.Slice::getLabel);
        splitter.setFirstComponent(withHeader("Leak Slices", wrapTable(slicesTable, true)));

        JBTextArea recordDetailsArea = createReadOnlyTextArea();
        ListTableModel<Model.LeakReport.Record> recordsModel = new ListTableModel<>(List.of(
                column("Name", Model.LeakReport.Record::getName),
                column("Index", Model.LeakReport.Record::getIndex),
                column("Paths", record -> safeSize(record.getPaths())),
                column("Description", Model.LeakReport.Record::getDesc)));
        recordsModel.setRows(leakReport == null ? List.of() : leakReport.getRecords());
        JBTable recordsTable = new JBTable(recordsModel);
        recordsTable.setAutoCreateRowSorter(true);
        recordsTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int selectedRow = recordsTable.getSelectedRow();
            if (selectedRow < 0) {
                return;
            }
            Model.LeakReport.Record row = recordsModel.row(recordsTable.convertRowIndexToModel(selectedRow));
            recordDetailsArea.setText(buildLeakRecordDetail(row));
            recordDetailsArea.setCaretPosition(0);
            if (row.getPaths() != null && !row.getPaths().isEmpty()) {
                loadInspector(row.getPaths().get(0).getObjectId());
            }
        });
        JPanel recordsPanel = new JPanel(new BorderLayout(0, 8));
        recordsPanel.add(wrapTable(recordsTable, true), BorderLayout.CENTER);
        recordsPanel.add(withHeader("Selected Path", new JScrollPane(recordDetailsArea)), BorderLayout.SOUTH);
        splitter.setSecondComponent(recordsPanel);
        panel.add(splitter, BorderLayout.CENTER);
        return panel;
    }

    private JComponent createDominatorTreeView() {
        PageView<? extends Model.DominatorTree.Item> roots = result.getAnalyzer()
                .getRootsOfDominatorTree(
                        Model.DominatorTree.Grouping.NONE, "retainedHeap", false, "", SearchType.BY_NAME, 1, 200);
        ListTableModel<Model.DominatorTree.Item> model = new ListTableModel<>(List.of(
                column("Label", Model.DominatorTree.Item::getLabel),
                column("Object Id", Model.DominatorTree.Item::getObjectId),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize())),
                column("Percent", item -> JifaUiFormatters.formatPercent(item.getPercent()))));
        model.setRows(castRows(roots == null ? null : roots.getData()));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.DominatorTree.Item::getObjectId);
        installObjectActions(table, model, Model.DominatorTree.Item::getObjectId, Model.DominatorTree.Item::getLabel);
        return withHeader("Top Dominators", wrapTable(table, true));
    }

    private JComponent createHistogramView() {
        PageView<Model.Histogram.Item> histogram = result.getAnalyzer()
                .getHistogram(
                        Model.Histogram.Grouping.BY_CLASS, null, "retainedSize", false, "", SearchType.BY_NAME, 1, 200);
        ListTableModel<Model.Histogram.Item> model = new ListTableModel<>(List.of(
                column("Label", Model.Histogram.Item::getLabel),
                column("Object Id", Model.Histogram.Item::getObjectId),
                column("Objects", Model.Histogram.Item::getNumberOfObjects),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize()))));
        model.setRows(pageRows(histogram));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.Histogram.Item::getObjectId);
        installObjectActions(table, model, Model.Histogram.Item::getObjectId, Model.Histogram.Item::getLabel);
        return withHeader("By Class Histogram", wrapTable(table, true));
    }

    private JComponent createThreadsView() {
        PageView<Model.Thread.Item> threads =
                result.getAnalyzer().getThreads("retainedHeap", false, "", SearchType.BY_NAME, 1, 200);
        Model.Thread.Summary summary = result.getAnalyzer().getSummaryOfThreads("", SearchType.BY_NAME);

        ListTableModel<Model.Thread.Item> model = new ListTableModel<>(List.of(
                column("Id", Model.Thread.Item::getObjectId),
                column("Name", Model.Thread.Item::getName),
                column("Thread Object", Model.Thread.Item::getObject),
                column("Context ClassLoader", Model.Thread.Item::getContextClassLoader),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize())),
                column("Daemon", Model.Thread.Item::isDaemon),
                column("Has Stack", Model.Thread.Item::isHasStack)));
        model.setRows(pageRows(threads));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.Thread.Item::getObjectId);
        installObjectActions(table, model, Model.Thread.Item::getObjectId, Model.Thread.Item::getName);

        JBTextArea stackArea = createReadOnlyTextArea();
        stackArea.setText("Select a thread to inspect its stack trace and local variables.");
        table.getSelectionModel().addListSelectionListener(event -> loadThreadStack(event, table, model, stackArea));

        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        summaryPanel.add(new javax.swing.JLabel("Total Size: " + JifaUiFormatters.formatBytes(summary.getTotalSize())));
        summaryPanel.add(
                new javax.swing.JLabel("Shallow Heap: " + JifaUiFormatters.formatBytes(summary.getShallowHeap())));
        summaryPanel.add(
                new javax.swing.JLabel("Retained Heap: " + JifaUiFormatters.formatBytes(summary.getRetainedHeap())));

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.55f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(wrapTable(table, true));
        splitter.setSecondComponent(withHeader("Stack / Locals", new JScrollPane(stackArea)));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(summaryPanel, BorderLayout.NORTH);
        panel.add(splitter, BorderLayout.CENTER);
        return panel;
    }

    private JComponent createClassLoadersView() {
        Model.ClassLoader.Summary summary = result.getAnalyzer().getSummaryOfClassLoaders();
        PageView<Model.ClassLoader.Item> rows = result.getAnalyzer().getClassLoaders(1, 200);

        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        summaryPanel.add(new javax.swing.JLabel("ClassLoaders: " + summary.getTotalSize()));
        summaryPanel.add(new javax.swing.JLabel("Defined Classes: " + summary.getDefinedClasses()));
        summaryPanel.add(new javax.swing.JLabel("Instances: " + summary.getNumberOfInstances()));

        ListTableModel<Model.ClassLoader.Item> model = new ListTableModel<>(List.of(
                column("Object Id", Model.ClassLoader.Item::getObjectId),
                column("Label", Model.ClassLoader.Item::getLabel),
                column("Prefix", Model.ClassLoader.Item::getPrefix),
                column("Defined Classes", Model.ClassLoader.Item::getDefinedClasses),
                column("Instances", Model.ClassLoader.Item::getNumberOfInstances),
                column("Has Parent", Model.ClassLoader.Item::isHasParent)));
        model.setRows(pageRows(rows));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.ClassLoader.Item::getObjectId);
        installObjectActions(table, model, Model.ClassLoader.Item::getObjectId, Model.ClassLoader.Item::getLabel);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(summaryPanel, BorderLayout.NORTH);
        panel.add(wrapTable(table, true), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createQueryView() {
        JBTextArea queryInput = createReadOnlyTextArea();
        queryInput.setEditable(true);
        queryInput.setText("SELECT * FROM java.lang.String s");

        JRadioButton oqlButton = new JRadioButton("OQL", true);
        JRadioButton sqlButton = new JRadioButton("SQL");
        ButtonGroup group = new ButtonGroup();
        group.add(oqlButton);
        group.add(sqlButton);

        JPanel resultHost = new JPanel(new BorderLayout());
        resultHost.add(
                new JBLabel("Enter an OQL/SQL query and click Run.", SwingConstants.CENTER), BorderLayout.CENTER);

        JButton runButton = new JButton(new AbstractAction("Run") {
            @Override
            public void actionPerformed(ActionEvent event) {
                String query = queryInput.getText().trim();
                if (query.isEmpty()) {
                    resultHost.removeAll();
                    resultHost.add(
                            new JBLabel("Query text cannot be empty.", SwingConstants.CENTER), BorderLayout.CENTER);
                    resultHost.revalidate();
                    resultHost.repaint();
                    return;
                }
                resultHost.removeAll();
                resultHost.add(new JBLabel("Running query...", SwingConstants.CENTER), BorderLayout.CENTER);
                resultHost.revalidate();
                resultHost.repaint();
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        JComponent component = oqlButton.isSelected() ? runOqlQuery(query) : runSqlQuery(query);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            resultHost.removeAll();
                            resultHost.add(component, BorderLayout.CENTER);
                            resultHost.revalidate();
                            resultHost.repaint();
                        });
                    } catch (Throwable throwable) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            resultHost.removeAll();
                            resultHost.add(wrapText("Query failed: " + throwable), BorderLayout.CENTER);
                            resultHost.revalidate();
                            resultHost.repaint();
                        });
                    }
                });
            }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(oqlButton);
        controls.add(sqlButton);
        controls.add(runButton);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(controls, BorderLayout.NORTH);
        top.add(new JScrollPane(queryInput), BorderLayout.CENTER);

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.26f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(top);
        splitter.setSecondComponent(resultHost);
        return splitter;
    }

    private JComponent runOqlQuery(String query) {
        Model.OQLResult oqlResult = result.getAnalyzer().getOQLResult(query, "", true, 1, 200);
        return renderQueryResult(oqlResult);
    }

    private JComponent runSqlQuery(String query) {
        Model.CalciteSQLResult sqlResult = result.getAnalyzer().getCalciteSQLResult(query, "", true, 1, 200);
        return renderQueryResult(sqlResult);
    }

    private JComponent renderQueryResult(Object queryResult) {
        if (queryResult instanceof Model.OQLResult.TableResult oqlTable) {
            return createDynamicEntryTable(oqlTable.getColumns(), oqlTable.getPv());
        }
        if (queryResult instanceof Model.CalciteSQLResult.TableResult sqlTable) {
            return createDynamicEntryTable(sqlTable.getColumns(), sqlTable.getPv());
        }
        if (queryResult instanceof Model.OQLResult.TreeResult oqlTree) {
            return createObjectTable(oqlTree.getPv(), "Query Tree");
        }
        if (queryResult instanceof Model.CalciteSQLResult.TreeResult sqlTree) {
            return createObjectTable(sqlTree.getPv(), "Query Tree");
        }
        if (queryResult instanceof Model.OQLResult.TextResult oqlText) {
            return wrapText(oqlText.getText());
        }
        if (queryResult instanceof Model.CalciteSQLResult.TextResult sqlText) {
            return wrapText(sqlText.getText());
        }
        return wrapText("Unsupported query result: " + queryResult);
    }

    private JComponent createGcRootsView() {
        List<Model.GCRoot.Item> roots = result.getAnalyzer().getGCRoots();

        ListTableModel<Model.GCRoot.Item> rootModel = new ListTableModel<>(List.of(
                column("Class", Model.GCRoot.Item::getClassName),
                column("Objects", Model.GCRoot.Item::getObjects),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize()))));
        rootModel.setRows(roots);
        JBTable rootTable = new JBTable(rootModel);
        rootTable.setAutoCreateRowSorter(false);

        ListTableModel<Model.GCRoot.Item> classModel = new ListTableModel<>(List.of(
                column("Class", Model.GCRoot.Item::getClassName),
                column("Objects", Model.GCRoot.Item::getObjects),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize()))));
        JBTable classTable = new JBTable(classModel);
        classTable.setAutoCreateRowSorter(false);

        ListTableModel<Model.JavaObject> objectModel = objectColumnsModel();
        JBTable objectTable = new JBTable(objectModel);
        objectTable.setAutoCreateRowSorter(true);
        attachInspectorSelection(objectTable, objectModel, Model.JavaObject::getObjectId);
        installObjectActions(objectTable, objectModel, Model.JavaObject::getObjectId, Model.JavaObject::getLabel);

        rootTable
                .getSelectionModel()
                .addListSelectionListener(event -> loadGcRootClasses(event, rootTable, classModel, objectModel));
        classTable
                .getSelectionModel()
                .addListSelectionListener(event -> loadGcRootObjects(event, rootTable, classTable, objectModel));

        OnePixelSplitter lower = new OnePixelSplitter(true, 0.40f);
        lower.setHonorComponentsMinimumSize(true);
        lower.setFirstComponent(withHeader("Classes", wrapTable(classTable, false)));
        lower.setSecondComponent(withHeader("Objects", wrapTable(objectTable, true)));

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.34f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(withHeader("GC Root Types", wrapTable(rootTable, false)));
        splitter.setSecondComponent(lower);
        return splitter;
    }

    private JComponent createDirectByteBuffersView() {
        Model.DirectByteBuffer.Summary summary = result.getAnalyzer().getSummaryOfDirectByteBuffers();
        PageView<Model.DirectByteBuffer.Item> rows = result.getAnalyzer().getDirectByteBuffers(1, 200);

        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        summaryPanel.add(new javax.swing.JLabel("Total: " + summary.getTotalSize()));
        summaryPanel.add(new javax.swing.JLabel("Position: " + summary.getPosition()));
        summaryPanel.add(new javax.swing.JLabel("Limit: " + summary.getLimit()));
        summaryPanel.add(new javax.swing.JLabel("Capacity: " + summary.getCapacity()));

        ListTableModel<Model.DirectByteBuffer.Item> model = new ListTableModel<>(List.of(
                column("Object Id", Model.DirectByteBuffer.Item::getObjectId),
                column("Label", Model.DirectByteBuffer.Item::getLabel),
                column("Position", Model.DirectByteBuffer.Item::getPosition),
                column("Limit", Model.DirectByteBuffer.Item::getLimit),
                column("Capacity", Model.DirectByteBuffer.Item::getCapacity)));
        model.setRows(pageRows(rows));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.DirectByteBuffer.Item::getObjectId);
        installObjectActions(
                table, model, Model.DirectByteBuffer.Item::getObjectId, Model.DirectByteBuffer.Item::getLabel);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(summaryPanel, BorderLayout.NORTH);
        panel.add(wrapTable(table, true), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createDuplicateClassesView() {
        PageView<Model.DuplicatedClass.ClassItem> classes =
                result.getAnalyzer().getDuplicatedClasses("", SearchType.BY_NAME, 1, 200);

        ListTableModel<Model.DuplicatedClass.ClassItem> classModel = new ListTableModel<>(List.of(
                column("Label", Model.DuplicatedClass.ClassItem::getLabel),
                column("Count", Model.DuplicatedClass.ClassItem::getCount),
                column("Index", Model.DuplicatedClass.ClassItem::getIndex)));
        classModel.setRows(pageRows(classes));
        JBTable classTable = new JBTable(classModel);
        classTable.setAutoCreateRowSorter(true);

        ListTableModel<Model.DuplicatedClass.ClassLoaderItem> loaderModel = new ListTableModel<>(List.of(
                column("Object Id", Model.DuplicatedClass.ClassLoaderItem::getObjectId),
                column("Label", Model.DuplicatedClass.ClassLoaderItem::getLabel),
                column("Suffix", Model.DuplicatedClass.ClassLoaderItem::getSuffix),
                column("Defined Classes", Model.DuplicatedClass.ClassLoaderItem::getDefinedClassesCount),
                column("Instances", Model.DuplicatedClass.ClassLoaderItem::getInstantiatedObjectsCount)));
        JBTable loaderTable = new JBTable(loaderModel);
        loaderTable.setAutoCreateRowSorter(true);
        attachInspectorSelection(loaderTable, loaderModel, Model.DuplicatedClass.ClassLoaderItem::getObjectId);
        installObjectActions(
                loaderTable,
                loaderModel,
                Model.DuplicatedClass.ClassLoaderItem::getObjectId,
                Model.DuplicatedClass.ClassLoaderItem::getLabel);
        classTable
                .getSelectionModel()
                .addListSelectionListener(
                        event -> loadDuplicatedClassLoaders(event, classTable, classModel, loaderModel));

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.42f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(withHeader("Duplicated Classes", wrapTable(classTable, true)));
        splitter.setSecondComponent(withHeader("ClassLoaders", wrapTable(loaderTable, true)));
        return splitter;
    }

    private JComponent createUnreachableObjectsView() {
        Model.UnreachableObject.Summary summary = result.getAnalyzer().getSummaryOfUnreachableObjects();
        PageView<Model.UnreachableObject.Item> rows = result.getAnalyzer().getUnreachableObjects(1, 200);

        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        summaryPanel.add(new javax.swing.JLabel("Total Types: " + summary.getTotalSize()));
        summaryPanel.add(new javax.swing.JLabel("Objects: " + summary.getObjects()));
        summaryPanel.add(
                new javax.swing.JLabel("Shallow Heap: " + JifaUiFormatters.formatBytes(summary.getShallowSize())));

        ListTableModel<Model.UnreachableObject.Item> model = new ListTableModel<>(List.of(
                column("Object Id", Model.UnreachableObject.Item::getObjectId),
                column("Class", Model.UnreachableObject.Item::getClassName),
                column("Objects", Model.UnreachableObject.Item::getObjects),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize()))));
        model.setRows(pageRows(rows));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.UnreachableObject.Item::getObjectId);
        installObjectActions(
                table, model, Model.UnreachableObject.Item::getObjectId, Model.UnreachableObject.Item::getClassName);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(summaryPanel, BorderLayout.NORTH);
        panel.add(wrapTable(table, true), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createPropertiesView(Map<String, String> entries) {
        Map<String, String> safeEntries = entries == null ? Map.of() : new LinkedHashMap<>(entries);
        ListTableModel<Map.Entry<String, String>> model =
                new ListTableModel<>(List.of(column("Key", Map.Entry::getKey), column("Value", Map.Entry::getValue)));
        model.setRows(new ArrayList<>(safeEntries.entrySet()));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        return wrapTable(table, true);
    }

    private JComponent createDynamicEntryTable(List<String> columns, PageView<?> pageView) {
        List<?> rows = pageRows(pageView);
        JBTable table = new JBTable(new DynamicEntryTableModel(columns, rows));
        table.setAutoCreateRowSorter(true);
        if (!rows.isEmpty() && rows.get(0) instanceof Model.OQLResult.TableResult.Entry) {
            table.getSelectionModel().addListSelectionListener(event -> {
                if (event.getValueIsAdjusting()) {
                    return;
                }
                int selectedRow = table.getSelectedRow();
                if (selectedRow < 0) {
                    return;
                }
                Object row = rows.get(table.convertRowIndexToModel(selectedRow));
                if (row instanceof Model.OQLResult.TableResult.Entry entry && entry.getObjectId() > 0) {
                    loadInspector(entry.getObjectId());
                }
                if (row instanceof Model.CalciteSQLResult.TableResult.Entry entry && entry.getObjectId() > 0) {
                    loadInspector(entry.getObjectId());
                }
            });
        }
        return wrapTable(table, true);
    }

    private JComponent createObjectTable(PageView<Model.JavaObject> rows, String title) {
        ListTableModel<Model.JavaObject> model = objectColumnsModel();
        model.setRows(pageRows(rows));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.JavaObject::getObjectId);
        installObjectActions(table, model, Model.JavaObject::getObjectId, Model.JavaObject::getLabel);
        return withHeader(title, wrapTable(table, true));
    }

    private <T> void installObjectActions(
            JBTable table,
            ListTableModel<T> model,
            ToIntFunction<T> objectIdExtractor,
            Function<T, String> labelExtractor) {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowPopup(event);
            }

            private void maybeShowPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int viewRow = table.rowAtPoint(event.getPoint());
                if (viewRow < 0) {
                    return;
                }
                table.setRowSelectionInterval(viewRow, viewRow);
                T row = model.row(table.convertRowIndexToModel(viewRow));
                int objectId = objectIdExtractor.applyAsInt(row);
                if (objectId <= 0) {
                    return;
                }
                String label = labelExtractor.apply(row);
                JPopupMenu menu = new JPopupMenu();
                menu.add(menuItem("Inspect", () -> loadInspector(objectId)));
                menu.add(menuItem("Outbounds", () -> openOutboundsTab(objectId, label)));
                menu.add(menuItem("Inbounds", () -> openInboundsTab(objectId, label)));
                menu.add(menuItem("Path to GC Roots", () -> openPathToGcRootsTab(objectId, label)));
                menu.add(menuItem("Merged GC Root Path", () -> openMergedGcRootsTab(objectId, label)));
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
        });
    }

    private JMenuItem menuItem(String title, Runnable action) {
        JMenuItem item = new JMenuItem(title);
        item.addActionListener(event -> action.run());
        return item;
    }

    private void openOutboundsTab(int objectId, String label) {
        openDynamicTab(
                "Outbounds: " + safeLabel(label, objectId),
                createObjectTable(result.getAnalyzer().getOutboundOfObject(objectId, 1, 200), "Outbound References"));
    }

    private void openInboundsTab(int objectId, String label) {
        openDynamicTab(
                "Inbounds: " + safeLabel(label, objectId),
                createObjectTable(result.getAnalyzer().getInboundOfObject(objectId, 1, 200), "Inbound References"));
    }

    private void openPathToGcRootsTab(int objectId, String label) {
        Model.GCRootPath.Item pathItem = result.getAnalyzer().getPathToGCRoots(objectId, 0, 200);
        openDynamicTab("GC Roots: " + safeLabel(label, objectId), wrapText(buildGcRootPathText(pathItem)));
    }

    private void openMergedGcRootsTab(int objectId, String label) {
        PageView<Model.GCRootPath.MergePathToGCRootsTreeNode> rows = result.getAnalyzer()
                .getRootsOfMergePathToGCRootsByObjectIds(
                        new int[] {objectId}, Model.GCRootPath.Grouping.FROM_GC_ROOTS, 1, 200);
        ListTableModel<Model.GCRootPath.MergePathToGCRootsTreeNode> model = new ListTableModel<>(List.of(
                column("Class", Model.GCRootPath.MergePathToGCRootsTreeNode::getClassName),
                column("Object Id", Model.GCRootPath.MergePathToGCRootsTreeNode::getObjectId),
                column("Ref Objects", Model.GCRootPath.MergePathToGCRootsTreeNode::getRefObjects),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowHeap())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedHeap()))));
        model.setRows(pageRows(rows));
        JBTable table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        attachInspectorSelection(table, model, Model.GCRootPath.MergePathToGCRootsTreeNode::getObjectId);
        installObjectActions(
                table,
                model,
                Model.GCRootPath.MergePathToGCRootsTreeNode::getObjectId,
                Model.GCRootPath.MergePathToGCRootsTreeNode::getClassName);
        openDynamicTab("Merged GC Roots: " + safeLabel(label, objectId), wrapTable(table, true));
    }

    private String buildGcRootPathText(Model.GCRootPath.Item pathItem) {
        if (pathItem == null || pathItem.getTree() == null) {
            return "No path to GC roots.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Count: ").append(pathItem.getCount()).append(System.lineSeparator());
        builder.append("Has More: ")
                .append(pathItem.isHasMore())
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        appendGcRootNode(builder, pathItem.getTree(), 0);
        return builder.toString();
    }

    private void appendGcRootNode(StringBuilder builder, Model.GCRootPath.Node node, int depth) {
        builder.append("  ".repeat(Math.max(0, depth)))
                .append("- ")
                .append(node.getLabel())
                .append(" [id=")
                .append(node.getObjectId())
                .append(", shallow=")
                .append(JifaUiFormatters.formatBytes(node.getShallowSize()))
                .append(", retained=")
                .append(JifaUiFormatters.formatBytes(node.getRetainedSize()))
                .append("]")
                .append(System.lineSeparator());
        if (node.getChildren() == null) {
            return;
        }
        for (Model.GCRootPath.Node child : node.getChildren()) {
            appendGcRootNode(builder, child, depth + 1);
        }
    }

    private void openDynamicTab(String title, JComponent component) {
        int existingIndex = tabs.indexOfTab(title);
        if (existingIndex >= 0) {
            tabs.setComponentAt(existingIndex, component);
            tabs.setSelectedIndex(existingIndex);
            return;
        }
        tabs.addTab(title, component);
        tabs.setSelectedComponent(component);
    }

    private String safeLabel(String label, int objectId) {
        return label == null || label.isBlank() ? ("#" + objectId) : label;
    }

    private ListTableModel<Model.JavaObject> objectColumnsModel() {
        return new ListTableModel<>(List.of(
                column("Object Id", Model.JavaObject::getObjectId),
                column("Label", Model.JavaObject::getLabel),
                column("Prefix", Model.JavaObject::getPrefix),
                column("Suffix", Model.JavaObject::getSuffix),
                column("Shallow Heap", item -> JifaUiFormatters.formatBytes(item.getShallowSize())),
                column("Retained Heap", item -> JifaUiFormatters.formatBytes(item.getRetainedSize()))));
    }

    private void loadGcRootClasses(
            ListSelectionEvent event,
            JBTable rootTable,
            ListTableModel<Model.GCRoot.Item> classModel,
            ListTableModel<Model.JavaObject> objectModel) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int selectedRow = rootTable.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        int rootTypeIndex = rootTable.convertRowIndexToModel(selectedRow);
        classModel.setRows(pageRows(result.getAnalyzer().getClassesOfGCRoot(rootTypeIndex, 1, 200)));
        objectModel.setRows(List.of());
    }

    private void loadGcRootObjects(
            ListSelectionEvent event,
            JBTable rootTable,
            JBTable classTable,
            ListTableModel<Model.JavaObject> objectModel) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int rootRow = rootTable.getSelectedRow();
        int classRow = classTable.getSelectedRow();
        if (rootRow < 0 || classRow < 0) {
            return;
        }
        int rootTypeIndex = rootTable.convertRowIndexToModel(rootRow);
        int classIndex = classTable.convertRowIndexToModel(classRow);
        objectModel.setRows(pageRows(result.getAnalyzer().getObjectsOfGCRoot(rootTypeIndex, classIndex, 1, 200)));
    }

    private void loadDuplicatedClassLoaders(
            ListSelectionEvent event,
            JBTable classTable,
            ListTableModel<Model.DuplicatedClass.ClassItem> classModel,
            ListTableModel<Model.DuplicatedClass.ClassLoaderItem> loaderModel) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int selectedRow = classTable.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        Model.DuplicatedClass.ClassItem row = classModel.row(classTable.convertRowIndexToModel(selectedRow));
        loaderModel.setRows(pageRows(result.getAnalyzer().getClassloadersOfDuplicatedClass(row.getIndex(), 1, 200)));
    }

    private void loadThreadStack(
            ListSelectionEvent event, JBTable table, ListTableModel<Model.Thread.Item> model, JBTextArea stackArea) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        Model.Thread.Item row = model.row(table.convertRowIndexToModel(selectedRow));
        stackArea.setText("Loading stack trace...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Model.Thread.StackFrame> stackFrames = result.getAnalyzer().getStackTrace(row.getObjectId());
            List<Model.Thread.LocalVariable> locals =
                    result.getAnalyzer().getLocalVariables(row.getObjectId(), 16, true);
            String text = buildThreadStackText(row, stackFrames, locals);
            ApplicationManager.getApplication().invokeLater(() -> {
                stackArea.setText(text);
                stackArea.setCaretPosition(0);
            });
        });
    }

    private String buildThreadStackText(
            Model.Thread.Item row, List<Model.Thread.StackFrame> stackFrames, List<Model.Thread.LocalVariable> locals) {
        StringBuilder builder = new StringBuilder();
        builder.append("Thread: ").append(row.getName()).append(System.lineSeparator());
        builder.append("Object: ").append(row.getObject()).append(System.lineSeparator());
        builder.append("Context ClassLoader: ")
                .append(row.getContextClassLoader())
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        builder.append("Stack Frames").append(System.lineSeparator());
        if (stackFrames == null || stackFrames.isEmpty()) {
            builder.append("No stack trace data.");
        } else {
            for (Model.Thread.StackFrame frame : stackFrames) {
                builder.append("- ").append(frame.getStack()).append(System.lineSeparator());
                if (frame.isHasLocal()) {
                    builder.append("  locals: yes");
                    if (frame.getMaxLocalsRetainedSize() > 0) {
                        builder.append(" (max retained ")
                                .append(JifaUiFormatters.formatBytes(frame.getMaxLocalsRetainedSize()))
                                .append(")");
                    }
                    builder.append(System.lineSeparator());
                }
            }
        }
        builder.append(System.lineSeparator()).append("Local Variables").append(System.lineSeparator());
        if (locals == null || locals.isEmpty()) {
            builder.append("No local variables.");
        } else {
            for (Model.Thread.LocalVariable local : locals) {
                builder.append("- ")
                        .append(local.getLabel())
                        .append(" [id=")
                        .append(local.getObjectId())
                        .append(", shallow=")
                        .append(JifaUiFormatters.formatBytes(local.getShallowSize()))
                        .append(", retained=")
                        .append(JifaUiFormatters.formatBytes(local.getRetainedSize()))
                        .append("]")
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private void loadInspector(int objectId) {
        if (objectId <= 0) {
            return;
        }
        inspectorTitle.setText("Inspector #" + objectId);
        inspectorSummaryArea.setText("Loading inspector...");
        inspectorValueArea.setText("Loading value...");
        inspectorFieldsModel.setRows(List.of());
        inspectorStaticFieldsModel.setRows(List.of());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Model.InspectorView inspectorView = result.getAnalyzer().getInspectorView(objectId);
            PageView<Model.FieldView> fields = result.getAnalyzer().getFields(objectId, 1, 200);
            PageView<Model.FieldView> staticFields = result.getAnalyzer().getStaticFields(objectId, 1, 200);
            String value = result.getAnalyzer().getObjectValue(objectId);
            ApplicationManager.getApplication().invokeLater(() -> {
                inspectorSummaryArea.setText(buildInspectorText(inspectorView));
                inspectorSummaryArea.setCaretPosition(0);
                inspectorFieldsModel.setRows(pageRows(fields));
                inspectorStaticFieldsModel.setRows(pageRows(staticFields));
                inspectorValueArea.setText(value == null || value.isBlank() ? "No resolved value." : value);
                inspectorValueArea.setCaretPosition(0);
            });
        });
    }

    private String buildInspectorText(Model.InspectorView inspectorView) {
        if (inspectorView == null) {
            return "No inspector data.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Object Address: ")
                .append(inspectorView.getObjectAddress())
                .append(System.lineSeparator());
        builder.append("Name: ").append(inspectorView.getName()).append(System.lineSeparator());
        builder.append("Class: ").append(inspectorView.getClassLabel()).append(System.lineSeparator());
        builder.append("Super Class: ")
                .append(inspectorView.getSuperClassName())
                .append(System.lineSeparator());
        builder.append("ClassLoader: ")
                .append(inspectorView.getClassLoaderLabel())
                .append(System.lineSeparator());
        builder.append("Shallow Heap: ")
                .append(JifaUiFormatters.formatBytes(inspectorView.getShallowSize()))
                .append(System.lineSeparator());
        builder.append("Retained Heap: ")
                .append(JifaUiFormatters.formatBytes(inspectorView.getRetainedSize()))
                .append(System.lineSeparator());
        if (inspectorView.getGcRootInfo() != null
                && !inspectorView.getGcRootInfo().isBlank()) {
            builder.append("GC Root Info: ")
                    .append(inspectorView.getGcRootInfo())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String buildOverviewText(Model.Overview.Details details) {
        StringBuilder builder = new StringBuilder();
        builder.append("Creation Time: ")
                .append(JifaUiFormatters.formatTimestampMillis(details.getCreationDate()))
                .append(System.lineSeparator());
        builder.append("Used Heap: ")
                .append(JifaUiFormatters.formatBytes(details.getUsedHeapSize()))
                .append(System.lineSeparator());
        builder.append("Objects: ").append(details.getNumberOfObjects()).append(System.lineSeparator());
        builder.append("Classes: ").append(details.getNumberOfClasses()).append(System.lineSeparator());
        builder.append("ClassLoaders: ")
                .append(details.getNumberOfClassLoaders())
                .append(System.lineSeparator());
        builder.append("GC Roots: ").append(details.getNumberOfGCRoots()).append(System.lineSeparator());
        builder.append("Identifier Size: ")
                .append(details.getIdentifierSize())
                .append(" B")
                .append(System.lineSeparator());
        builder.append(System.lineSeparator()).append("JVM Options").append(System.lineSeparator());
        if (details.getJvmOptions() == null || details.getJvmOptions().isEmpty()) {
            builder.append("No JVM options found.");
        } else {
            details.getJvmOptions()
                    .forEach(option -> builder.append("- ").append(option).append(System.lineSeparator()));
        }
        return builder.toString();
    }

    private String buildLeakSummary(Model.LeakReport leakReport) {
        if (leakReport == null) {
            return "No leak report available.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Useful: ").append(leakReport.isUseful()).append(System.lineSeparator());
        if (leakReport.getName() != null) {
            builder.append("Name: ").append(leakReport.getName()).append(System.lineSeparator());
        }
        if (leakReport.getInfo() != null) {
            builder.append("Info: ").append(leakReport.getInfo()).append(System.lineSeparator());
        }
        builder.append("Slices: ").append(safeSize(leakReport.getSlices())).append(System.lineSeparator());
        builder.append("Records: ").append(safeSize(leakReport.getRecords())).append(System.lineSeparator());
        return builder.toString();
    }

    private String buildLeakRecordDetail(Model.LeakReport.Record record) {
        StringBuilder builder = new StringBuilder();
        builder.append(record.getName()).append(System.lineSeparator());
        if (record.getDesc() != null) {
            builder.append(record.getDesc()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        if (record.getPaths() == null || record.getPaths().isEmpty()) {
            builder.append("No shortest paths.");
            return builder.toString();
        }
        builder.append("Shortest Paths").append(System.lineSeparator());
        for (Model.LeakReport.ShortestPath path : record.getPaths()) {
            appendShortestPath(builder, path, 0);
        }
        return builder.toString();
    }

    private void appendShortestPath(StringBuilder builder, Model.LeakReport.ShortestPath path, int depth) {
        builder.append("  ".repeat(Math.max(0, depth)))
                .append("- ")
                .append(path.getLabel())
                .append(" [id=")
                .append(path.getObjectId())
                .append(", shallow=")
                .append(JifaUiFormatters.formatBytes(path.getShallowSize()))
                .append(", retained=")
                .append(JifaUiFormatters.formatBytes(path.getRetainedSize()))
                .append("]")
                .append(System.lineSeparator());
        if (path.getChildren() == null) {
            return;
        }
        for (Model.LeakReport.ShortestPath child : path.getChildren()) {
            appendShortestPath(builder, child, depth + 1);
        }
    }

    private <T> void attachInspectorSelection(
            JBTable table, ListTableModel<T> model, ToIntFunction<T> objectIdExtractor) {
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int selectedRow = table.getSelectedRow();
            if (selectedRow < 0) {
                return;
            }
            T row = model.row(table.convertRowIndexToModel(selectedRow));
            loadInspector(objectIdExtractor.applyAsInt(row));
        });
    }

    private <T> List<T> pageRows(PageView<T> pageView) {
        return pageView == null || pageView.getData() == null ? List.of() : pageView.getData();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> castRows(List<?> rows) {
        return rows == null ? List.of() : (List<T>) rows;
    }

    private int safeSize(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private JComponent withHeader(String title, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private JComponent wrapTable(JBTable table, boolean sortable) {
        table.setAutoCreateRowSorter(sortable);
        return new JScrollPane(table);
    }

    private JComponent wrapText(String text) {
        return new JScrollPane(textAreaWithContent(text));
    }

    private JBTextArea textAreaWithContent(String text) {
        JBTextArea area = createReadOnlyTextArea();
        area.setText(text == null ? "" : text);
        area.setCaretPosition(0);
        return area;
    }

    private static JBTextArea createReadOnlyTextArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static <T> Column<T> column(String title, Function<T, Object> valueProvider) {
        return new Column<>(title, valueProvider);
    }

    private enum TabKey {
        OVERVIEW(REQUIRED_TAB_TITLES.get(0)),
        LEAK_SUSPECTS(REQUIRED_TAB_TITLES.get(1)),
        DOMINATOR_TREE(REQUIRED_TAB_TITLES.get(2)),
        HISTOGRAM(REQUIRED_TAB_TITLES.get(3)),
        THREADS(REQUIRED_TAB_TITLES.get(4)),
        CLASS_LOADERS(REQUIRED_TAB_TITLES.get(5)),
        QUERY(REQUIRED_TAB_TITLES.get(6)),
        GC_ROOTS(REQUIRED_TAB_TITLES.get(7)),
        DIRECT_BYTE_BUFFERS(REQUIRED_TAB_TITLES.get(8)),
        DUPLICATE_CLASSES(REQUIRED_TAB_TITLES.get(9)),
        UNREACHABLE_OBJECTS(REQUIRED_TAB_TITLES.get(10)),
        SYSTEM_PROPERTIES(REQUIRED_TAB_TITLES.get(11)),
        ENV_VARIABLES(REQUIRED_TAB_TITLES.get(12));

        private final String title;

        TabKey(String title) {
            this.title = title;
        }

        private static TabKey fromIndex(int index) {
            if (index < 0 || index >= values().length) {
                return null;
            }
            return values()[index];
        }
    }

    private record Column<T>(String title, Function<T, Object> valueProvider) {}

    private static final class ListTableModel<T> extends AbstractTableModel {

        private final List<Column<T>> columns;
        private final List<T> rows = new ArrayList<>();

        private ListTableModel(List<Column<T>> columns) {
            this.columns = columns;
        }

        private void setRows(List<T> items) {
            rows.clear();
            if (items != null) {
                rows.addAll(items);
            }
            fireTableDataChanged();
        }

        private T row(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return columns.get(columnIndex).valueProvider().apply(rows.get(rowIndex));
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).title();
        }
    }

    private static final class DynamicEntryTableModel extends AbstractTableModel {

        private final List<String> columns;
        private final List<?> rows;

        private DynamicEntryTableModel(List<String> columns, List<?> rows) {
            this.columns = columns == null ? List.of() : columns;
            this.rows = rows == null ? List.of() : rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Object row = rows.get(rowIndex);
            if (row instanceof Model.OQLResult.TableResult.Entry entry) {
                return columnIndex < entry.getValues().size()
                        ? entry.getValues().get(columnIndex)
                        : "";
            }
            if (row instanceof Model.CalciteSQLResult.TableResult.Entry entry) {
                return columnIndex < entry.getValues().size()
                        ? entry.getValues().get(columnIndex)
                        : "";
            }
            return "";
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }
    }
}
