package com.github.ideanovel.action;

import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.ui.NovelReaderToolWindowFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 打开本地 txt 小说。
 */
public class OpenLocalNovelAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        // 先确保工具窗已经建好，否则面板还没注册监听，进度和内容刷不出来
        ToolWindow window = ToolWindowManager.getInstance(project)
                .getToolWindow(NovelReaderToolWindowFactory.TOOL_WINDOW_ID);
        if (window != null) {
            window.show(() -> chooseAndOpen(project));
        } else {
            chooseAndOpen(project);
        }
    }

    private static void chooseAndOpen(Project project) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("txt");
        descriptor.setTitle("选择小说文件");
        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            NovelReaderService.getInstance().openLocal(file.getPath());
        }
    }
}
