package com.alibaba.arthas.idea.workbench.action;

import com.alibaba.arthas.idea.workbench.ArthasWorkbenchBundle;
import com.alibaba.arthas.idea.workbench.service.JifaWebRuntimeService;
import com.alibaba.arthas.idea.workbench.util.UiToolkit;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;

/**
 * 统一封装浏览器版 Jifa Web 的后台准备流程。
 */
public final class JifaWebOpenSupport {

    private JifaWebOpenSupport() {}

    public static void openHome(Project project) {
        run(project, null);
    }

    public static void openAnalysis(Project project, Path target) {
        run(project, target);
    }

    private static void run(Project project, Path target) {
        if (!isUiContextAlive(project)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ProgressIndicator indicator = new EmptyProgressIndicator();
            try {
                JifaWebRuntimeService service =
                        ApplicationManager.getApplication().getService(JifaWebRuntimeService.class);
                JifaWebRuntimeService.LaunchResult result = target == null
                        ? service.prepareHomePage(indicator)
                        : service.prepareAnalysisPage(target, indicator);
                invokeLaterIfAlive(project, () -> {
                    UiToolkit.openInBrowser(project, result.url());
                    UiToolkit.notifyInfo(
                            project,
                            message(
                                    "jifa.web.notify.ready",
                                    result.summary().discovered(),
                                    result.summary().reused(),
                                    result.summary().uploaded(),
                                    result.summary().deleted(),
                                    result.url()));
                });
            } catch (Throwable throwable) {
                invokeLaterIfAlive(
                        project,
                        () -> UiToolkit.notifyError(
                                project, message("jifa.web.error.prepare_failed", throwable.getMessage())));
            }
        });
    }

    private static void invokeLaterIfAlive(Project project, Runnable runnable) {
        if (!isUiContextAlive(project)) {
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!isUiContextAlive(project)) {
                                return;
                            }
                            runnable.run();
                        },
                        ModalityState.any());
    }

    private static boolean isUiContextAlive(Project project) {
        return ApplicationManager.getApplication() != null
                && !ApplicationManager.getApplication().isDisposed()
                && UiToolkit.isProjectAlive(project);
    }

    private static String message(String key, Object... params) {
        return ArthasWorkbenchBundle.message(key, params);
    }
}
