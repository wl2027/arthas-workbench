package com.alibaba.arthas.idea.workbench.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowFactory;

/**
 * 创建 Jifa JFR Analysis Tool Window。
 */
public final class JifaAnalysisToolWindowFactory implements ToolWindowFactory {

    private static final String PANEL_PROPERTY = "arthas.workbench.jifa.toolWindowPanel";

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null);
        toolWindow.setSplitMode(false, null);
        if (toolWindow.getComponent().getClientProperty(PANEL_PROPERTY) != null) {
            return;
        }
        JifaAnalysisToolWindowPanel panel = new JifaAnalysisToolWindowPanel(project, toolWindow);
        toolWindow.getComponent().putClientProperty(PANEL_PROPERTY, panel);
        Disposer.register(toolWindow.getContentManager(), panel);
        Disposer.register(
                toolWindow.getContentManager(),
                () -> toolWindow.getComponent().putClientProperty(PANEL_PROPERTY, null));
    }

    @Override
    public boolean shouldBeAvailable(Project project) {
        return true;
    }

    static String propertyKey() {
        return PANEL_PROPERTY;
    }
}
