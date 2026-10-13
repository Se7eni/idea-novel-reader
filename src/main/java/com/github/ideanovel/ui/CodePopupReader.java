package com.github.ideanovel.ui;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.settings.NovelSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;

import javax.swing.SwingUtilities;
import java.awt.Point;

/**
 * 代码弹出框的宿主：把 {@link CodePopupContent} 装进一个 JBPopup，浮在光标附近。
 *
 * <p>拆成「内容 + 宿主」两个类是有原因的：宿主这边全是平台 API
 * （JBPopupFactory 在 IDE 之外根本用不了），混在一起就没法离线渲染验证观感。
 * 分开之后，长得对不对看 {@link CodePopupContent}，浮在哪、怎么关看这里。
 *
 * <p>视觉上刻意做成 IDE 自带「快速文档」弹窗的样子：光标旁的浮动窗口、
 * 顶部一行文件名、正文是源码。别人瞟一眼只会以为你在看方法说明。
 *
 * <p>同时只允许存在一个：再按一次快捷键是关掉，不是再叠一个出来。
 */
public final class CodePopupReader implements Disposable, NovelReaderService.NovelListener {

    /** 当前打开的弹出框。同时开一个就够了，多了反而挡屏幕 */
    private static CodePopupReader active;

    private final Project project;
    private final CodePopupContent content = new CodePopupContent();

    private JBPopup popup;
    private boolean disposed;
    private boolean listenerAttached;

    private CodePopupReader(Project project) {
        this.project = project;
        content.setNav(new CodePopupContent.Nav() {
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
                NovelReaderService.getInstance().prevChapter();
            }

            @Override
            public void changeFontSize(int delta) {
                // 复用阅读器那套字号设置：同一个「阅读字号」，两处不该各记一份
                NovelReaderService.getInstance().changeFontSize(delta);
                content.setCodeFontSize(codeFontSizeFromSettings());
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

    private boolean isOpen() {
        return !disposed && popup != null && !popup.isDisposed();
    }

    private void show() {
        content.setCodeFontSize(codeFontSizeFromSettings());
        refresh();

        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setResizable(true)
                .setMovable(true)
                // 拿到焦点按键才落到弹窗自己身上（否则 Esc/翻章键还是被编辑器吃了）
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                // 记住尺寸和位置，下次打开还是用户拖过的大小
                .setDimensionServiceKey(project, "IdeaNovel.CodePopup", false)
                .setCancelCallback(() -> {
                    detach();
                    return true;
                })
                .createPopup();

        // 注意：JBPopup 只能在创建时给标题，运行时没有 setTitle（已用反射核对过 API），
        // 而标题要随章节变（Chapter0004.java → Chapter0005.java），
        // 所以文件名画在内容里，不用平台的标题栏。
        attach();
        active = this;
        popup.show(anchorPoint());
    }

    /** 每章正文变了就重渲染一次，弹出框里也能翻章 */
    private void refresh() {
        NovelReaderService service = NovelReaderService.getInstance();
        Book book = service.getBook();
        if (book == null) {
            content.setPlaceholder("还没有打开小说。先在「摸鱼小说」窗口里打开一本，再按快捷键唤出这个弹窗。");
            return;
        }
        content.setChapter(service.currentChapterTitle(), service.currentText(),
                service.getChapterIndex(), book.getChapters().size());
    }

    /** 弹窗里的源码字号取阅读字号 -2，比正文小一点，更像真源码视图 */
    private int codeFontSizeFromSettings() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        int base = (s == null || s.fontSize <= 0) ? 16 : s.fontSize;
        return Math.max(9, Math.min(48, base - 2));
    }

    /**
     * 浮在光标下方；没有编辑器（比如刚打开 IDE）就浮在 IDE 窗口中间。
     *
     * 用光标行下方而不是屏幕正中：这正是 IDE 快速文档出现的位置，
     * 视线不用移动，也最容易被人当成系统弹的。
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

    /** 关掉弹窗。Esc、点外面、再按一次快捷键、项目关闭都会走到这里，所以必须幂等 */
    public void close() {
        if (isOpen()) {
            popup.cancel();
        }
        detach();
    }

    // ---------------- 服务回调 ----------------

    @Override
    public void chapterChanged(int index, String text) {
        // 在弹窗里翻章、或在侧边栏翻章，都要把新内容灌进来
        if (isOpen()) {
            refresh();
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
        if (!isOpen()) {
            return;
        }
        content.setCodeFontSize(codeFontSizeFromSettings());
        // 配色跟着编辑器方案走，设置页改了主题要重取
        SwingUtilities.invokeLater(content::applyThemeColors);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (popup != null && !popup.isDisposed()) {
            popup.cancel();
        }
        detach();
    }
}
