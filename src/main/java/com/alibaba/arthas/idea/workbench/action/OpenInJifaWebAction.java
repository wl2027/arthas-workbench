package com.alibaba.arthas.idea.workbench.action;

import com.alibaba.arthas.idea.workbench.ArthasWorkbenchBundle;
import com.alibaba.arthas.idea.workbench.analysis.JifaAnalysisFacade;
import com.alibaba.arthas.idea.workbench.util.UiToolkit;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 在浏览器中打开本地 Jifa Web 分析页。
 */
public final class OpenInJifaWebAction extends AnAction implements DumbAware {

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean supported = file != null && isSupported(file);
        event.getPresentation().setEnabledAndVisible(supported);
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) {
            return;
        }
        if (!isSupported(file)) {
            UiToolkit.notifyWarn(project, message("jifa.notify.unsupported_file", file.getPath()));
            return;
        }
        JifaWebOpenSupport.openAnalysis(project, Path.of(file.getPath()));
    }

    private boolean isSupported(VirtualFile file) {
        if (!file.isInLocalFileSystem() || file.isDirectory()) {
            return false;
        }
        try {
            return JifaAnalysisFacade.isSupported(Path.of(file.getPath()));
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static String message(String key, Object... params) {
        return ArthasWorkbenchBundle.message(key, params);
    }
}
