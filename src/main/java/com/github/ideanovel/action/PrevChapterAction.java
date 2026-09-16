package com.github.ideanovel.action;

import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.ui.ReaderPanel;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 上一章。
 */
public class PrevChapterAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        NovelReaderService service = NovelReaderService.getInstance();
        if (service.getBook() == null) {
            return;
        }
        ReaderPanel panel = project.getUserData(ReaderPanel.PANEL_KEY);
        if (panel != null) {
            panel.flushProgress();
        }
        service.prevChapter();
    }
}
