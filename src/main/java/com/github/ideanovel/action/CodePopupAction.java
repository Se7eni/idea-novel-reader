package com.github.ideanovel.action;

import com.github.ideanovel.ui.CodePopupReader;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 代码提示框阅读：在光标旁浮出一小块悬停提示样式的小窗来读小说。
 *
 * 不开工具窗、不动侧边栏，Esc 就散，适合只想瞟两眼又不想留痕迹的场合。
 */
public class CodePopupAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        CodePopupReader.toggle(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // 没有项目时（比如欢迎界面）点了也没用，直接置灰
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
