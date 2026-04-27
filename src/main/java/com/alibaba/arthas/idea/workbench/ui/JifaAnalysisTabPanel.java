package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.ArthasWorkbenchBundle;
import com.alibaba.arthas.idea.workbench.analysis.JifaAnalysisFacade;
import com.alibaba.arthas.idea.workbench.analysis.JifaAnalysisResult;
import com.alibaba.arthas.idea.workbench.analysis.JifaArtifactDescriptor;
import com.alibaba.arthas.idea.workbench.analysis.JifaGcLogAnalysisResult;
import com.alibaba.arthas.idea.workbench.analysis.JifaHprofAnalysisResult;
import com.alibaba.arthas.idea.workbench.analysis.JifaJfrAnalysisResult;
import com.alibaba.arthas.idea.workbench.analysis.JifaThreadDumpAnalysisResult;
import com.alibaba.arthas.idea.workbench.util.UiToolkit;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import org.eclipse.jifa.analysis.listener.ProgressListener;

/**
 * 单个 Jifa 分析页签。
 */
public final class JifaAnalysisTabPanel extends JPanel implements Disposable {

    static final Key<String> FILE_PATH_KEY = Key.create("arthas.workbench.jifa.filePath");

    private static final String CARD_LOADING = "loading";
    private static final String CARD_READY = "ready";
    private static final String CARD_ERROR = "error";

    private final Project project;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final JBLabel statusLabel = new JBLabel();
    private final JBLabel filePathLabel = new JBLabel();
    private final JBLabel hintLabel = new JBLabel();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JBTextArea loadingLogArea = new JBTextArea();
    private final JBTextArea errorArea = new JBTextArea();
    private final JPanel readyPanel = new JPanel(new BorderLayout());
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final Timer loadingTimer = new Timer(300, event -> refreshLoadingHint());

    private VirtualFile file;
    private long analyzedModificationStamp = -1L;
    private long loadingStartedAtMillis;
    private long loadingFileSizeBytes = -1L;
    private String loadingHintMessage = "";
    private boolean disposed;

    public JifaAnalysisTabPanel(Project project, VirtualFile file) {
        super(new BorderLayout(0, 8));
        this.project = project;
        this.file = file;

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(createHeader(), BorderLayout.NORTH);
        add(cardPanel, BorderLayout.CENTER);

        initializeCards();
        reloadAnalysis(false);
    }

    public void refreshIfStale(VirtualFile latestFile) {
        this.file = latestFile;
        filePathLabel.setText(latestFile.getPath());
        if (analyzedModificationStamp != latestFile.getModificationStamp()) {
            reloadAnalysis(false);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        loadingTimer.stop();
    }

    private JComponent createHeader() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));

        JPanel textPanel = new JPanel(new BorderLayout(0, 2));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        filePathLabel.setText(file.getPath());
        hintLabel.setText(" ");
        textPanel.add(statusLabel, BorderLayout.NORTH);
        textPanel.add(filePathLabel, BorderLayout.CENTER);
        textPanel.add(hintLabel, BorderLayout.SOUTH);

        JButton refreshButton = new JButton(message("jifa.button.refresh"));
        refreshButton.addActionListener(event -> reloadAnalysis(true));
        JButton openDirectoryButton = new JButton(message("jifa.button.open_directory"));
        openDirectoryButton.addActionListener(event -> UiToolkit.openDirectory(project, file.getPath()));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(refreshButton);
        actions.add(openDirectoryButton);

        panel.add(textPanel, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void initializeCards() {
        progressBar.setStringPainted(true);
        loadingLogArea.setEditable(false);
        loadingLogArea.setLineWrap(false);
        errorArea.setEditable(false);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);

        JPanel loadingPanel = new JPanel(new BorderLayout(0, 8));
        loadingPanel.add(progressBar, BorderLayout.NORTH);
        loadingPanel.add(new JBScrollPane(loadingLogArea), BorderLayout.CENTER);

        JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.add(new JBScrollPane(errorArea), BorderLayout.CENTER);

        cardPanel.add(loadingPanel, CARD_LOADING);
        cardPanel.add(readyPanel, CARD_READY);
        cardPanel.add(errorPanel, CARD_ERROR);
    }

    private void reloadAnalysis(boolean forceRefresh) {
        Path path = Path.of(file.getPath());
        int requestId = requestCounter.incrementAndGet();
        loadingStartedAtMillis = System.currentTimeMillis();
        loadingFileSizeBytes = fileSize(path);
        loadingHintMessage = message("jifa.loading.hint");
        loadingTimer.start();
        updateLoading(new LoadingState(message("jifa.loading.detect"), "", 0.0));
        cardLayout.show(cardPanel, CARD_LOADING);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            PanelProgressListener listener = new PanelProgressListener(
                    state -> ApplicationManager.getApplication().invokeLater(() -> {
                        if (shouldIgnore(requestId)) {
                            return;
                        }
                        updateLoading(state);
                    }));
            try {
                JifaArtifactDescriptor descriptor = JifaAnalysisFacade.detect(path);
                if (descriptor == null) {
                    throw new IllegalStateException(message("jifa.notify.unsupported_file", path));
                }
                listener.externalUpdate(new LoadingState(
                        message("jifa.loading.analyze", descriptor.type().getDisplayName()),
                        listener.log(),
                        listener.percent()));
                JifaAnalysisResult result = JifaAnalysisFacade.analyze(descriptor, listener, forceRefresh);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (shouldIgnore(requestId)) {
                        return;
                    }
                    analyzedModificationStamp = file.getModificationStamp();
                    showReadyState(result);
                });
            } catch (Throwable throwable) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (shouldIgnore(requestId)) {
                        return;
                    }
                    showErrorState(throwable, listener.log());
                });
            }
        });
    }

    private boolean shouldIgnore(int requestId) {
        return disposed || requestId != requestCounter.get();
    }

    private void updateLoading(LoadingState state) {
        statusLabel.setText(state.title());
        loadingLogArea.setText(state.log());
        loadingLogArea.setCaretPosition(loadingLogArea.getDocument().getLength());
        int percent = (int) Math.round(Math.max(0.0, Math.min(1.0, state.percent())) * 100);
        progressBar.setIndeterminate(percent <= 0);
        progressBar.setValue(percent);
        progressBar.setString(percent <= 0 ? message("jifa.loading.pending") : percent + "%");
        refreshLoadingHint();
    }

    private void showReadyState(JifaAnalysisResult result) {
        loadingTimer.stop();
        readyPanel.removeAll();
        statusLabel.setText(result.getType().getDisplayName());
        hintLabel.setText(" ");
        JComponent view =
                switch (result) {
                    case JifaJfrAnalysisResult jfrResult -> new JfrAnalysisView(jfrResult);
                    case JifaGcLogAnalysisResult gcLogResult -> new GcLogAnalysisView(gcLogResult);
                    case JifaThreadDumpAnalysisResult threadDumpResult -> new ThreadDumpAnalysisView(threadDumpResult);
                    case JifaHprofAnalysisResult hprofResult -> new HprofAnalysisView(hprofResult);
                    default ->
                        throw new IllegalStateException("Unsupported analysis result: "
                                + result.getClass().getName());
                };
        readyPanel.add(view, BorderLayout.CENTER);
        readyPanel.revalidate();
        readyPanel.repaint();
        cardLayout.show(cardPanel, CARD_READY);
    }

    private void showErrorState(Throwable throwable, String progressLog) {
        loadingTimer.stop();
        statusLabel.setText(message("jifa.error.analysis_failed", throwable.getMessage()));
        hintLabel.setText(" ");
        errorArea.setText(progressLog + System.lineSeparator() + System.lineSeparator() + throwable);
        errorArea.setCaretPosition(0);
        cardLayout.show(cardPanel, CARD_ERROR);
    }

    private void refreshLoadingHint() {
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - loadingStartedAtMillis);
        String sizeText = loadingFileSizeBytes < 0 ? "-" : JifaUiFormatters.formatBytes(loadingFileSizeBytes);
        hintLabel.setText(message(
                "jifa.loading.meta",
                JifaUiFormatters.formatDurationMillis(elapsedMillis),
                sizeText,
                loadingHintMessage));
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static String message(String key, Object... params) {
        return ArthasWorkbenchBundle.message(key, params);
    }

    private record LoadingState(String title, String log, double percent) {}

    private static final class PanelProgressListener implements ProgressListener {

        private final StringBuilder logBuilder = new StringBuilder();
        private final Consumer<LoadingState> stateConsumer;
        private int workload;
        private int worked;
        private String currentTask = "";

        private PanelProgressListener(Consumer<LoadingState> stateConsumer) {
            this.stateConsumer = stateConsumer;
        }

        @Override
        public synchronized void beginTask(String name, int workload) {
            this.currentTask = name;
            this.workload = Math.max(workload, 0);
            this.worked = 0;
            appendLine(name);
            publish();
        }

        @Override
        public synchronized void subTask(String name) {
            this.currentTask = name;
            appendLine(name);
            publish();
        }

        @Override
        public synchronized void worked(int workload) {
            this.worked += workload;
            publish();
        }

        @Override
        public synchronized void sendUserMessage(Level level, String message, Throwable throwable) {
            appendLine("[" + level + "] " + message);
            if (throwable != null) {
                appendLine(throwable.toString());
            }
            publish();
        }

        @Override
        public synchronized String log() {
            return logBuilder.toString();
        }

        @Override
        public synchronized double percent() {
            if (workload <= 0) {
                return 0.0;
            }
            return Math.min(1.0, (double) worked / workload);
        }

        private synchronized void externalUpdate(LoadingState state) {
            stateConsumer.accept(state);
        }

        private void appendLine(String line) {
            if (!logBuilder.isEmpty()) {
                logBuilder.append(System.lineSeparator());
            }
            logBuilder.append(line);
        }

        private void publish() {
            stateConsumer.accept(new LoadingState(currentTask, log(), percent()));
        }
    }
}
