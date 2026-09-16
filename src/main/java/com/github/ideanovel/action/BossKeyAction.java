package com.github.ideanovel.action;

import com.github.ideanovel.ui.NovelReaderToolWindowFactory;
import com.github.ideanovel.ui.ReaderPanel;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 老板键：在阅读界面和伪装界面之间一键切换。
 */
public class BossKeyAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ReaderPanel panel = project.getUserData(ReaderPanel.PANEL_KEY);
        if (panel != null) {
            panel.toggleDisguise();
            return;
        }
        // 面板还没建，先把工具窗叫出来
        ToolWindow window = ToolWindowManager.getInstance(project)
                .getToolWindow(NovelReaderToolWindowFactory.TOOL_WINDOW_ID);
        if (window != null) {
            window.show(null);
        }
    }
}
