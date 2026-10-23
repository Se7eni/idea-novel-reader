package com.github.ideanovel.ui;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.settings.NovelSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.InputValidatorEx;
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

    /** 恢复位置时，若目标章节 != 当前章节，先把段位置暂存到这里，等 chapterChanged 到达再落地 */
    private int pendingChunkOnRestore = -1;

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
                // 空格切换自动翻段：用上次填的间隔，没填过就用默认 5 秒
                if (content.isAutoAdvancing()) {
                    content.stopAuto();
                } else {
                    NovelSettingsState s = NovelSettingsState.getInstance();
                    int seconds = (s == null || s.popupAutoSeconds <= 0) ? 5 : s.popupAutoSeconds;
                    content.setAutoAdvance(seconds);
                }
            }

            @Override
            public void changeAutoSeconds() {
                // S 键：输入间隔秒数并开始自动翻段（只允许正整数）
                int seconds = askAutoSeconds(project);
                if (seconds > 0) {
                    content.setAutoAdvance(seconds);
                }
            }
        });
    }

    /**
     * 快捷键 / 工具栏入口：已开着就关掉，没开就先问自动换句秒数再打开。
     * 流程：按一次 → 弹数字输入框 → 确定开始自动换句 → 再按一次关闭。
     */
    public static void toggle(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        if (active != null && active.isOpen()) {
            active.close();
            return;
        }
        int seconds = askAutoSeconds(project);
        if (seconds <= 0) {
            return; // 用户取消
        }
        CodePopupReader reader = new CodePopupReader(project);
        Disposer.register(project, reader);
        reader.show();
        reader.content.setAutoAdvance(seconds);
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

    /** 输入自动换句间隔秒数。取消或非法返回 -1；合法返回正整数并记住 */
    private static int askAutoSeconds(Project project) {
        NovelSettingsState settings = NovelSettingsState.getInstance();
        int last = settings == null ? 5 : Math.max(1, settings.popupAutoSeconds);
        String input = Messages.showInputDialog(project,
                "自动换句间隔（秒）：", "摸鱼小说 · 代码弹出框",
                Messages.getQuestionIcon(), String.valueOf(last), positiveIntValidator());
        if (input == null) {
            return -1;
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
            // 校验器已经拦住，到不了这里
        }
        return -1;
    }

    /** 只允许正整数的校验器：输入框层面限制，非数字/非正整数点不了「确定」 */
    private static InputValidator positiveIntValidator() {
        return new InputValidatorEx() {
            @Override
            public String getErrorText(String inputString) {
                return checkInput(inputString) ? null : "只能输入正整数（秒）";
            }

            @Override
            public boolean checkInput(String inputString) {
                if (inputString == null) {
                    return false;
                }
                try {
                    return Integer.parseInt(inputString.trim()) > 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        };
    }

    private boolean isOpen() {
        return !disposed && popup != null && !popup.isDisposed();
    }

    private void show() {
        restorePosition();
        refresh();

        // 不可缩放、不可拖动、不记尺寸——提示框就该是提示框的样子，大小由内容决定。
        // 注意：setCancelOnClickOutside(false)——挂机自动翻段时用户要一边看代码一边瞟弹窗，
        // 点代码不该把弹窗关掉；关掉靠 Esc 或再按一次快捷键。
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setResizable(false)
                .setMovable(false)
                // 拿到焦点 Esc/方向键/空格/S 才落到内容自己的键位表上
                .setRequestFocus(true)
                .setCancelOnClickOutside(false)
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

    /** 打开时恢复到上次读到的章节 + 段位置（记忆位置只按当前书匹配） */
    private void restorePosition() {
        NovelReaderService service = NovelReaderService.getInstance();
        Book book = service.getBook();
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (book == null || s == null || s.popupBookId == null) {
            return;
        }
        if (!s.popupBookId.equals(book.getId())) {
            return;
        }
        int targetChapter = s.popupChapterIndex;
        if (targetChapter < 0 || targetChapter >= book.getChapters().size()) {
            return;
        }
        if (targetChapter != service.getChapterIndex()) {
            // 章节不同：跳章是异步的（chapterChanged 稍后到），段位置先暂存，
            // 等 chapterChanged 到达再落到内容里，避免被旧章节的首次渲染消费掉
            pendingChunkOnRestore = s.popupChunkIndex;
            service.gotoChapter(targetChapter);
        } else {
            // 章节相同：直接指定段位置，show() 里的 refresh() 会带着它重渲染
            content.setPendingChunk(s.popupChunkIndex);
        }
    }

    /** 关掉时记住当前书 + 章节 + 段位置 */
    private void savePosition() {
        NovelReaderService service = NovelReaderService.getInstance();
        Book book = service.getBook();
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (book == null || s == null) {
            return;
        }
        s.popupBookId = book.getId();
        s.popupChapterIndex = service.getChapterIndex();
        s.popupChunkIndex = content.chunkIndex();
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

    /** 关掉提示框。Esc、再按一次快捷键、项目关闭都会走到这里，所以必须幂等 */
    public void close() {
        savePosition();
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
            if (pendingChunkOnRestore >= 0) {
                content.setPendingChunk(pendingChunkOnRestore);
                pendingChunkOnRestore = -1;
            }
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
        // 弹窗字号固定 13，不跟阅读字号联动（单行显示需要字号可控），设置页改动这里不处理
    }

    @Override
    public void dispose() {
        disposed = true;
        savePosition();
        content.stopAuto();
        if (popup != null && !popup.isDisposed()) {
            popup.cancel();
        }
        detach();
    }
}
