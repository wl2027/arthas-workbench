package com.alibaba.arthas.idea.workbench.ui;

import com.alibaba.arthas.idea.workbench.ArthasWorkbenchBundle;
import com.alibaba.arthas.idea.workbench.util.UiToolkit;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jifa JFR Analysis Tool Window 的内容容器。
 */
public final class JifaAnalysisToolWindowPanel implements Disposable {

    public static final String TOOL_WINDOW_ID = "Jifa JFR Analysis";

    private final ContentManager contentManager;
    private final Map<String, JifaAnalysisTabPanel> analysisPanels = new LinkedHashMap<>();
    private final Map<String, Content> contents = new LinkedHashMap<>();
    private Content placeholderContent;

    public JifaAnalysisToolWindowPanel(Project project, ToolWindow toolWindow) {
        this.contentManager = toolWindow.getContentManager();
        resetContentManager();
        this.contentManager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(ContentManagerEvent event) {
                String path = event.getContent().getUserData(JifaAnalysisTabPanel.FILE_PATH_KEY);
                if (path == null) {
                    return;
                }
                analysisPanels.remove(path);
                contents.remove(path);
                if (contents.isEmpty()) {
                    ensurePlaceholderContent();
                }
            }
        });
        ensurePlaceholderContent();
    }

    public static void openInToolWindow(Project project, ToolWindow toolWindow, VirtualFile file) {
        Object panel = toolWindow.getComponent().getClientProperty(JifaAnalysisToolWindowFactory.propertyKey());
        if (panel instanceof JifaAnalysisToolWindowPanel toolWindowPanel) {
            toolWindowPanel.openAnalysis(project, file);
        }
    }

    void openAnalysis(Project project, VirtualFile file) {
        String filePath = file.getPath();
        removePlaceholderContent();
        JifaAnalysisTabPanel panel = analysisPanels.get(filePath);
        Content content = contents.get(filePath);
        if (panel == null || content == null) {
            panel = new JifaAnalysisTabPanel(project, file);
            content = ContentFactory.getInstance().createContent(panel, file.getName(), false);
            content.setCloseable(true);
            content.setDescription(UiToolkit.compactSingleLine(filePath, 160));
            content.putUserData(JifaAnalysisTabPanel.FILE_PATH_KEY, filePath);
            content.setDisposer(panel);
            analysisPanels.put(filePath, panel);
            contents.put(filePath, content);
            contentManager.addContent(content);
        } else {
            panel.refreshIfStale(file);
        }
        contentManager.setSelectedContent(content);
    }

    private void resetContentManager() {
        for (Content content : contentManager.getContents()) {
            contentManager.removeContent(content, true);
        }
        placeholderContent = null;
        analysisPanels.clear();
        contents.clear();
    }

    private void ensurePlaceholderContent() {
        if (placeholderContent != null && placeholderContent.getManager() == contentManager) {
            if (!contentManager.isSelected(placeholderContent)) {
                contentManager.setSelectedContent(placeholderContent);
            }
            return;
        }
        JBTextArea area = new JBTextArea(ArthasWorkbenchBundle.message("jifa.placeholder.message"));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        placeholderContent = ContentFactory.getInstance()
                .createContent(new JBScrollPane(area), ArthasWorkbenchBundle.message("jifa.placeholder.title"), false);
        placeholderContent.setCloseable(false);
        contentManager.addContent(placeholderContent);
        contentManager.setSelectedContent(placeholderContent);
    }

    private void removePlaceholderContent() {
        if (placeholderContent != null && placeholderContent.getManager() == contentManager) {
            contentManager.removeContent(placeholderContent, true);
        }
        placeholderContent = null;
    }

    @Override
    public void dispose() {
        removePlaceholderContent();
        List<Content> existingContents = new ArrayList<>(contents.values());
        for (Content content : existingContents) {
            contentManager.removeContent(content, true);
        }
        analysisPanels.clear();
        contents.clear();
    }
}
