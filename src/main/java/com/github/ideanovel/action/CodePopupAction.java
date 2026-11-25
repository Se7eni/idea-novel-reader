package com.github.ideanovel.action;

import com.github.ideanovel.ui.CodePopupReader;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
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
        // 只控制 enabled，不动 visible。
        // 之前用 setEnabledAndVisible(false)：在没有项目（欢迎页）时会把 action 一并隐藏，
        // 部分上下文（编辑器焦点）下会连带快捷键不响应。这里只置灰即可。
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // 只读 project 引用，不碰 PSI/VFS，后台线程更新即可，避免 EDT 卡顿
        return ActionUpdateThread.BGT;
    }
}
