package com.github.ideanovel.action;

import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.ui.NovelReaderToolWindowFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 打开网络小说目录页。
 */
public class OpenRemoteNovelAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ToolWindow window = ToolWindowManager.getInstance(project)
                .getToolWindow(NovelReaderToolWindowFactory.TOOL_WINDOW_ID);
        if (window != null) {
            window.show(() -> inputAndOpen(project));
        } else {
            inputAndOpen(project);
        }
    }

    private static void inputAndOpen(Project project) {
        String url = Messages.showInputDialog(project, "输入小说目录页网址：",
                "打开网络小说", Messages.getQuestionIcon());
        if (url != null && !url.trim().isEmpty()) {
            NovelReaderService.getInstance().openRemote(url.trim());
        }
    }
}
