package com.github.ideanovel.action;

import com.github.ideanovel.service.NovelReaderService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/**
 * 放大阅读区字号。
 */
public class IncreaseFontSizeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        NovelReaderService.getInstance().changeFontSize(2);
    }
}
