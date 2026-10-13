package com.github.ideanovel.action;

import com.github.ideanovel.ui.CodePopupReader;
import com.github.ideanovel.ui.StatusBarChunkWidgetFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 上一段（默认小键盘 -）。
 *
 * <p>落点规则同 {@link NextChunkAction}：弹出框开着翻弹出框，
 * 否则翻状态栏。章首再往前自动落到上一章最后一段。
 */
public class PrevChunkAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        if (CodePopupReader.pageIfOpen(false)) {
            return;
        }
        StatusBarChunkWidgetFactory.page(project, false);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
