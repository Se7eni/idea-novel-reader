package com.github.ideanovel.ui;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.settings.NovelSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;

import java.awt.Point;

/**
 * 代码提示框的宿主：把 {@link MiniTooltipContent} 装进一个 JBPopup，贴在光标旁。
 *
 * <p>「内容 / 宿主」分家的原因同大窗（1.0.9 的 CodePopupContent，后被本类替换）：
 * JBPopupFactory 这些平台 API 在 IDE 之外用不了，内容面板必须保持纯 Swing
 * 才能离线渲染验证。
 *
 * <p>视觉上就是 IDE 的悬停提示：一小块浮层、正文加 (n/N) 段进度，
 * 贴在光标下方的代码上。别人瞟一眼只会以为是编辑器弹了什么提示。
 *
 * <p>同时只允许存在一个：再按一次快捷键是关掉，不是再叠一个出来。
 */
public final class CodePopupReader implements Disposable, NovelReaderService.NovelListener {

    /** 当前打开的提示框。同时开一个就够了，多了反而挡代码 */
    private static CodePopupReader active;

    private final Project project;
    private final MiniTooltipContent content = new MiniTooltipContent();

    /**
     * 「在章首往前翻」时置位：等下一章内容到达后直接落在最后一段。
     * 这是内容和宿主之间的握手——内容自己发 prevChapter 请求，但新章节
     * 是通过 chapterChanged 事件回来的，请求意图只能由宿主捎带过去。
     */
    private boolean jumpToEndPending;

    private JBPopup popup;
    private boolean disposed;
    private boolean listenerAttached;

    private CodePopupReader(Project project) {
        this.project = project;
        content.setResizeListener(size -> {
            // 内容变了（翻段/字号变化）尺寸会变。JBPopup 的 setSize 直接定尺寸，
            // 比 setPreferredSize + pack 更可靠——pack 可能因为内容组件还没重排而量错。
            if (popup != null && !popup.isDisposed()) {
                popup.setSize(new java.awt.Dimension(size.width, size.height));
            }
        });
        content.setNav(new MiniTooltipContent.Nav() {
            @Override
            public void close() {
                CodePopupReader.this.close();
            }

            @Override
            public void nextChapter() {
                NovelReaderService.getInstance().nextChapter();
            }

            @Override
            public void prevChapter() {
                jumpToEndPending = true;
                NovelReaderService.getInstance().prevChapter();
            }

            @Override
            public void toggleAutoAdvance() {
                // 空格切换自动翻段：用上次定时弹窗填的间隔，没填过就用默认 5 秒
                if (content.isAutoAdvancing()) {
                    content.stopAuto();
                } else {
                    NovelSettingsState s = NovelSettingsState.getInstance();
                    int seconds = (s == null || s.popupAutoSeconds <= 0) ? 5 : s.popupAutoSeconds;
                    content.setAutoAdvance(seconds);
                }
            }
        });
    }

    /** 快捷键 / 工具栏入口：已开着就关掉，没开就开 */
    public static void toggle(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        if (active != null && active.isOpen()) {
            active.close();
            return;
        }
        CodePopupReader reader = new CodePopupReader(project);
        Disposer.register(project, reader);
        reader.show();
    }

    /**
     * 小键盘 + / - 的入口之一：弹出框开着时优先翻弹出框（用户正看着它）。
     * 返回 false 表示弹出框没开，动作该落到状态栏 widget 上。
     */
    public static boolean pageIfOpen(boolean forward) {
        if (active != null && active.isOpen()) {
            if (forward) {
                active.content.nextChunk();
            } else {
                active.content.prevChunk();
            }
            return true;
        }
        return false;
    }

    /**
     * 定时弹出框阅读：先问间隔秒数，然后弹出小窗并每隔几秒自动翻段。
     * 再按一次快捷键（或 Esc / 点外面）即停止。
     */
    public static void toggleTimed(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        if (active != null && active.isOpen()) {
            active.close();
            return;
        }
        Integer seconds = askInterval(project);
        if (seconds == null) {
            return;
        }
        CodePopupReader reader = new CodePopupReader(project);
        Disposer.register(project, reader);
        reader.show();
        reader.content.setAutoAdvance(seconds);
    }

    /** 输入间隔时长。取消返回 null；非法输入弹错误框并放弃本次 */
    private static Integer askInterval(Project project) {
        NovelSettingsState settings = NovelSettingsState.getInstance();
        int last = settings == null ? 5 : Math.max(1, settings.popupAutoSeconds);
        String input = Messages.showInputDialog(project,
                "请输入间隔时长(正整数/秒)", "摸鱼小说 · 定时弹出框",
                Messages.getQuestionIcon(), String.valueOf(last), null);
        if (input == null) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(input.trim());
            if (seconds > 0) {
                if (settings != null) {
                    settings.popupAutoSeconds = seconds;
                }
                return seconds;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的错误提示
        }
        Messages.showErrorDialog(project, "间隔时长必须是正整数（秒）", "摸鱼小说");
        return null;
    }

    private boolean isOpen() {
        return !disposed && popup != null && !popup.isDisposed();
    }

    private void show() {
        content.setFontSize(fontFromSettings());
        refresh();

        // 和大窗的区别：不可缩放、不可拖动、不记尺寸——提示框就该是提示框的样子，
        // 大小由内容决定，位置每次都重新贴到光标旁。
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setResizable(false)
                .setMovable(false)
                // 拿到焦点 Esc/方向键才落到内容自己的键位表上
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setCancelCallback(() -> {
                    detach();
                    return true;
                })
                .createPopup();

        attach();
        active = this;
        popup.show(anchorPoint());
    }

    /** 章节正文变了就重新切段。jumpToEndPending 只消费一次 */
    private void refresh() {
        NovelReaderService service = NovelReaderService.getInstance();
        Book book = service.getBook();
        if (book == null) {
            jumpToEndPending = false;
            content.setPlaceholder("还没有打开小说。先在「摸鱼小说」窗口里打开一本，再按 Ctrl+Alt+Shift+C。");
            return;
        }
        String text = service.currentText();
        // 网络章节还在异步加载：显示「加载中」而不是停在上一个片段，让用户知道它在动
        if (service.isLoadingChapter() && (text == null || text.isEmpty())) {
            content.setPlaceholder("正在加载章节…");
            return;
        }
        boolean jumpToEnd = jumpToEndPending;
        jumpToEndPending = false;
        content.setChapterTitle(service.currentChapterTitle());
        content.setChapter(text, service.getChapterIndex(), jumpToEnd);
    }

    /** 提示框字号直接用阅读字号：两处一个来源，设置页改了这里跟着变 */
    private int fontFromSettings() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        int size = (s == null || s.fontSize <= 0) ? 16 : s.fontSize;
        return Math.max(NovelReaderService.MIN_FONT_SIZE, Math.min(NovelReaderService.MAX_FONT_SIZE, size));
    }

    /**
     * 贴在光标下方。用光标行而不是屏幕正中：这正是 IDE 悬停提示出现的位置，
     * 视线不用移动，也最容易被人当成编辑器自己弹的。
     */
    private RelativePoint anchorPoint() {
        Editor editor = selectedEditor();
        if (editor != null) {
            Point caret = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition());
            Point target = new Point(caret.x + 24, caret.y + editor.getLineHeight() + 6);
            return new RelativePoint(editor.getContentComponent(), target);
        }
        IdeFrame frame = ideFrame();
        if (frame != null && frame.getComponent() != null) {
            return RelativePoint.getCenterOf(frame.getComponent());
        }
        return RelativePoint.getCenterOf(content);
    }

    private Editor selectedEditor() {
        try {
            return FileEditorManager.getInstance(project).getSelectedTextEditor();
        } catch (Exception ignored) {
            return null;
        }
    }

    private IdeFrame ideFrame() {
        try {
            return WindowManager.getInstance().getIdeFrame(project);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void attach() {
        if (!listenerAttached) {
            NovelReaderService.getInstance().addListener(this);
            listenerAttached = true;
        }
    }

    private void detach() {
        try {
            if (listenerAttached) {
                NovelReaderService.getInstance().removeListener(this);
            }
        } catch (Exception ignored) {
            // 关闭阶段服务可能已经不可用，不影响收尾
        }
        listenerAttached = false;
        if (active == this) {
            active = null;
        }
    }

    /** 关掉提示框。Esc、点外面、再按一次快捷键、项目关闭都会走到这里，所以必须幂等 */
    public void close() {
        content.stopAuto();
        if (isOpen()) {
            popup.cancel();
        }
        detach();
    }

    // ---------------- 服务回调 ----------------

    @Override
    public void chapterChanged(int index, String text) {
        if (isOpen()) {
            refresh();
        }
    }

    @Override
    public void loading(String tip) {
        // 网络章节异步加载时，把小窗内容换成「加载中」，别停在旧片段上
        if (isOpen()) {
            content.setPlaceholder(tip == null ? "正在加载…" : tip);
        }
    }

    @Override
    public void bookChanged(Book book) {
        if (isOpen()) {
            refresh();
        }
    }

    @Override
    public void settingsChanged() {
        if (isOpen()) {
            content.setFontSize(fontFromSettings());
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        content.stopAuto();
        if (popup != null && !popup.isDisposed()) {
            popup.cancel();
        }
        detach();
    }
}
