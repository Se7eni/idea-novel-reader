package com.github.ideanovel.ui;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.service.NovelReaderService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 左下角状态栏阅读：当前段正文直接显示在 IDE 底部状态栏。
 *
 * <p>参考 chapter_reader 的招牌形态——正文塞进状态栏一小条，
 * 是所有阅读方式里最不显眼的一种：不占编辑区、不弹任何浮层，
 * 瞟一眼视线都不用挪。翻段用小键盘 {@code +} / {@code -}（见
 * NextChunkAction / PrevChunkAction，可在 Keymap 里改）。
 *
 * <p>widget 的生命周期跟着状态栏走（每个窗口一个实例），
 * 内容跟着 NovelReaderService 的章节事件走。章内位置由自己的
 * {@link ChunkCursor} 管理，与弹出框的游标相互独立——两处同时开时
 * 各翻各的，不会互相打架。
 */
public final class StatusBarChunkWidgetFactory implements StatusBarWidgetFactory {

    /** 每个项目当前活着的 widget，翻段动作靠它找到目标 */
    private static final Map<Project, ChunkWidget> WIDGETS = new ConcurrentHashMap<>();

    /** 翻段动作的入口：向前（+）或向后（-）。没有 widget 时静默 */
    public static void page(Project project, boolean forward) {
        if (project == null) {
            return;
        }
        ChunkWidget widget = WIDGETS.get(project);
        if (widget != null) {
            widget.page(forward);
        }
    }

    // ---------------- StatusBarWidgetFactory ----------------

    @Override
    public @NotNull String getId() {
        return "IdeaNovel.ChunkWidget";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "摸鱼小说：状态栏阅读";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        ChunkWidget widget = new ChunkWidget(project);
        WIDGETS.put(project, widget);
        Disposer.register(widget, () -> WIDGETS.remove(project, widget));
        return widget;
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        if (widget instanceof ChunkWidget) {
            Disposer.dispose(widget);
        }
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean isConfigurable() {
        // 显示在状态栏设置里，用户可以像关其他 widget 一样关掉它
        return true;
    }

    // ---------------- widget 本体 ----------------

    static final class ChunkWidget implements CustomStatusBarWidget, NovelReaderService.NovelListener {

        private final Project project;
        private final JLabel label = new JLabel(" ");
        private final ChunkCursor cursor = new ChunkCursor();

        /** 从章首往回翻时置位：等上一章内容到了直接落最后一段 */
        private boolean jumpToEndPending;

        /** 章正文是否已到达。bookChanged 只带书不带正文，正文要等 chapterChanged */
        private boolean contentReady;

        ChunkWidget(Project project) {
            this.project = project;
            label.setHorizontalAlignment(SwingConstants.LEFT);
            // 状态栏字号跟 UI 走，只保证别比状态栏其他文字大
            Font base = label.getFont();
            label.setFont(base.deriveFont((float) Math.max(11, base.getSize() - 1)));
            label.setToolTipText("摸鱼小说 · 小键盘 + / - 翻段，点击打开阅读窗口");
            // 点击打开侧边栏工具窗：从状态栏到「正经读」只要一下
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openToolWindow();
                }
            });
            NovelReaderService.getInstance().addListener(this);
        }

        private void openToolWindow() {
            try {
                var toolWindow = ToolWindowManager.getInstance(project).getToolWindow("摸鱼小说");
                if (toolWindow != null) {
                    toolWindow.show();
                }
            } catch (Exception ignored) {
                // 项目关闭阶段什么都可能拿不到
            }
        }

        /** 小键盘 + / - 的落点。章尾往后越到下一章，章首往前越到上一章末尾 */
        void page(boolean forward) {
            boolean moved = forward ? cursor.next() : cursor.prev();
            if (moved) {
                refreshLabel();
                return;
            }
            // 跨章：跟弹出框同一套握手——next 正常回到新章第一段，
            // prev 要带 jumpToEnd，让上一章从最后一段开始
            jumpToEndPending = !forward;
            NovelReaderService service = NovelReaderService.getInstance();
            if (forward) {
                service.nextChapter();
            } else {
                service.prevChapter();
            }
        }

        private void refreshLabel() {
            NovelReaderService service = NovelReaderService.getInstance();
            Book book = service.getBook();
            if (book == null) {
                label.setText("摸鱼小说 · 未打开");
                label.setToolTipText("点击打开阅读窗口，或 Tools > 摸鱼小说");
                return;
            }
            if (!contentReady) {
                label.setText("摸鱼小说 · 加载中…");
            } else {
                label.setText(cursor.display());
            }
            label.setToolTipText("<html>" + escape(service.currentChapterTitle())
                    + "<br>第 " + (service.getChapterIndex() + 1) + " / "
                    + book.getChapters().size() + " 章 · 小键盘 + / - 翻段</html>");
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
        }

        // ---------------- 服务回调 ----------------

        @Override
        public void chapterChanged(int index, String text) {
            boolean jumpToEnd = jumpToEndPending;
            jumpToEndPending = false;
            contentReady = text != null && !text.isBlank();
            cursor.setChapter(text == null ? "" : text, jumpToEnd);
            refreshLabel();
        }

        @Override
        public void bookChanged(Book book) {
            jumpToEndPending = false;
            contentReady = false;
            refreshLabel();
        }

        @Override
        public void settingsChanged() {
            refreshLabel();
        }

        // ---------------- widget 装配 ----------------

        @Override
        public @NotNull String ID() {
            return "IdeaNovel.ChunkWidget";
        }

        @Override
        public javax.swing.JComponent getComponent() {
            return label;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            // 装进状态栏时服务里可能早就有书在看，先把当前内容放上去
            NovelReaderService service = NovelReaderService.getInstance();
            Book book = service.getBook();
            if (book != null) {
                String text = service.currentText();
                contentReady = text != null && !text.isBlank();
                cursor.setChapter(text == null ? "" : text, false);
            }
            refreshLabel();
        }

        @Override
        public void dispose() {
            try {
                NovelReaderService.getInstance().removeListener(this);
            } catch (Exception ignored) {
                // 应用关闭阶段服务可能已经没了
            }
        }
    }
}
