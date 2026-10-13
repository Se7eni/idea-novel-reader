package com.github.ideanovel.action;

import com.github.ideanovel.ui.CodePopupReader;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 定时弹出框阅读：先输入间隔秒数，然后弹出小窗每隔几秒自动翻段。
 *
 * 挂机摸鱼的形态——手完全不用动，提示框自己往后翻，
 * 翻到章尾自动进下一章。再按一次快捷键 / Esc / 点外面即停止。
 */
public class CodePopupTimedAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        CodePopupReader.toggleTimed(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
