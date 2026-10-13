package com.github.ideanovel.action;

import com.github.ideanovel.ui.CodePopupReader;
import com.github.ideanovel.ui.StatusBarChunkWidgetFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 下一段（默认小键盘 +）。
 *
 * <p>落点规则：弹出框开着就翻弹出框（用户正看着它），
 * 否则翻状态栏的段游标。章尾再往后自动进入下一章第一段。
 * 键位可在 Settings > Keymap 里改。
 */
public class NextChunkAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        if (CodePopupReader.pageIfOpen(true)) {
            return;
        }
        StatusBarChunkWidgetFactory.page(project, true);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
