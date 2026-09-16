package com.github.ideanovel.service;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.model.Chapter;
import com.github.ideanovel.model.ReadingProgress;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.parser.ChapterParser;
import com.github.ideanovel.settings.NovelSettingsState;
import com.github.ideanovel.source.LocalFileSource;
import com.github.ideanovel.source.RemoteNovelSource;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用级阅读服务：管当前书、当前章节、异步加载和进度读写。
 * UI 通过监听器被动更新，不反向依赖界面。
 */
public final class NovelReaderService {

    private static final Logger LOG = Logger.getInstance(NovelReaderService.class);
    private static final String GROUP_ID = "Idea Novel Reader";

    /** 字号可调范围 */
    public static final int MIN_FONT_SIZE = 9;
    public static final int MAX_FONT_SIZE = 48;

    /** UI 监听回调，全部在 EDT 触发 */
    public interface NovelListener {
        default void bookChanged(Book book) {
        }

        default void chapterChanged(int index, String text) {
        }

        default void loading(String tip) {
        }

        default void settingsChanged() {
        }

        /** 状态栏上的临时提示，比如调字号时回显当前值 */
        default void tip(String text) {
        }
    }

    private final List<NovelListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Book currentBook;
    private volatile int chapterIndex;
    private volatile boolean loadingChapter;

    private NovelReaderService() {
    }

    public static NovelReaderService getInstance() {
        return ApplicationManager.getApplication().getService(NovelReaderService.class);
    }

    public void addListener(NovelListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(NovelListener listener) {
        listeners.remove(listener);
    }

    public Book getBook() {
        return currentBook;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public boolean isLoadingChapter() {
        return loadingChapter;
    }

    /** 当前章节正文 */
    public String currentText() {
        Book book = currentBook;
        if (book == null) {
            return "";
        }
        return book.chapterText(chapterIndex);
    }

    public String currentChapterTitle() {
        Book book = currentBook;
        if (book == null) {
            return "";
        }
        Chapter c = book.chapterAt(chapterIndex);
        return c == null ? "" : c.getTitle();
    }

    // ---------------- 打开书籍 ----------------

    public void openLocal(String path) {
        runAsync("正在读取本地小说...", () -> {
            Book book = new LocalFileSource().load(path);
            return book;
        });
    }

    public void openRemote(String url) {
        runAsync("正在解析网络小说...", () -> new RemoteNovelSource().load(url));
    }

    private void runAsync(String tip, Loader loader) {
        fireLoading(tip);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Book book = loader.load();
                ApplicationManager.getApplication().invokeLater(() -> setBook(book));
            } catch (Exception e) {
                LOG.warn("加载小说失败", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    fireLoading(null);
                    notify("加载失败：" + e.getMessage(), NotificationType.ERROR);
                });
            }
        });
    }

    private interface Loader {
        Book load() throws Exception;
    }

    private void setBook(Book book) {
        currentBook = book;
        NovelSettingsState settings = NovelSettingsState.getInstance();

        int index = 0;
        if (settings != null && settings.rememberProgress) {
            ReadingProgress p = settings.findProgress(book.getId());
            if (p != null && p.chapterIndex >= 0 && p.chapterIndex < book.getChapters().size()) {
                index = p.chapterIndex;
            }
        }
        chapterIndex = index;
        fireBookChanged(book);
        gotoChapter(index, true);
    }

    // ---------------- 章节切换 ----------------

    public void gotoChapter(int index) {
        gotoChapter(index, false);
    }

    private void gotoChapter(int index, boolean force) {
        Book book = currentBook;
        if (book == null) {
            return;
        }
        if (index < 0 || index >= book.getChapters().size()) {
            return;
        }
        if (!force && index == chapterIndex && loadingChapter) {
            return;
        }
        chapterIndex = index;

        Chapter c = book.chapterAt(index);
        if (c == null) {
            return;
        }

        // 网络章节正文还没拉，先异步取
        if (book.isLazyChapters() && !c.isRemoteLoaded() && c.getUrl() != null) {
            loadingChapter = true;
            fireLoading("正在加载章节...");
            String url = c.getUrl();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    String text = new RemoteNovelSource().loadChapterBody(url);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        loadingChapter = false;
                        Chapter cur = currentBook == null ? null : currentBook.chapterAt(chapterIndex);
                        // 用户可能已经翻走了，只更新仍然对应的那一章
                        if (cur != null && url.equals(cur.getUrl())) {
                            cur.setRemoteContent(text.isEmpty() ? "（本章内容为空，可能来源站点有反爬限制）" : text);
                            fireChapterChanged(chapterIndex, text);
                        }
                        fireLoading(null);
                    });
                } catch (Exception e) {
                    LOG.warn("加载章节失败: " + url, e);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        loadingChapter = false;
                        fireLoading(null);
                        Chapter cur = currentBook == null ? null : currentBook.chapterAt(chapterIndex);
                        if (cur != null && url.equals(cur.getUrl())) {
                            cur.setRemoteContent("（章节加载失败：" + e.getMessage() + "）");
                            fireChapterChanged(chapterIndex, cur.getRemoteContent());
                        }
                    });
                }
            });
            return;
        }

        fireChapterChanged(index, book.chapterText(index));
    }

    public void nextChapter() {
        if (currentBook == null) {
            return;
        }
        gotoChapter(chapterIndex + 1);
    }

    public void prevChapter() {
        if (currentBook == null) {
            return;
        }
        gotoChapter(chapterIndex - 1);
    }

    // ---------------- 字号调节 ----------------

    /**
     * 调整正文字号，delta 为正放大、为负缩小。
     * 改完立刻刷新界面并在状态栏回显当前字号。
     */
    public void changeFontSize(int delta) {
        NovelSettingsState settings = NovelSettingsState.getInstance();
        if (settings == null) {
            return;
        }
        int current = settings.fontSize <= 0 ? 16 : settings.fontSize;
        int next = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, current + delta));
        if (next == current) {
            fireTip("字号已到" + (delta > 0 ? "上限" : "下限") + "：" + next);
            return;
        }
        settings.fontSize = next;
        fireSettingsChanged();
        // tip 排在 settingsChanged 之后发，避免被状态栏刷新覆盖
        fireTip("字号：" + next);
    }

    // ---------------- 进度 ----------------

    public void saveProgress(float chapterRatio) {
        saveProgressAt(chapterIndex, chapterRatio);
    }

    /** 保存指定章节的进度，切章时用得上 */
    public void saveProgressAt(int index, float chapterRatio) {
        Book book = currentBook;
        NovelSettingsState settings = NovelSettingsState.getInstance();
        if (book == null || settings == null || !settings.rememberProgress) {
            return;
        }
        ReadingProgress p = settings.findProgress(book.getId());
        if (p == null) {
            p = new ReadingProgress(book.getId(), book.getTitle(), book.getType(), book.getLocation());
        }
        p.chapterIndex = Math.max(0, index);
        p.chapterRatio = Math.max(0f, Math.min(1f, chapterRatio));
        p.updatedAt = System.currentTimeMillis();
        settings.saveProgress(p);
    }

    /** 上次读到本章的位置，用于恢复滚动 */
    public float savedChapterRatio() {
        Book book = currentBook;
        NovelSettingsState settings = NovelSettingsState.getInstance();
        if (book == null || settings == null) {
            return 0f;
        }
        ReadingProgress p = settings.findProgress(book.getId());
        if (p == null || p.chapterIndex != chapterIndex) {
            return 0f;
        }
        return p.chapterRatio;
    }

    /** 重新按当前设置切分章节（改了正则之后用） */
    public void reparse() {
        Book book = currentBook;
        if (book == null || book.getType() != SourceType.LOCAL || book.getContent() == null) {
            return;
        }
        NovelSettingsState settings = NovelSettingsState.getInstance();
        List<Chapter> chapters = ChapterParser.parse(
                book.getContent(),
                settings == null || settings.useRegexChapter,
                settings == null ? null : settings.chapterRegex,
                settings == null ? 4000 : settings.chunkSize);
        book.setChapters(chapters);
        int keep = Math.min(chapterIndex, Math.max(0, chapters.size() - 1));
        chapterIndex = keep;
        fireBookChanged(book);
        gotoChapter(keep, true);
    }

    // ---------------- 事件分发 ----------------

    private void fireBookChanged(Book book) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (NovelListener l : listeners) {
                l.bookChanged(book);
            }
        });
    }

    private void fireChapterChanged(int index, String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (NovelListener l : listeners) {
                l.chapterChanged(index, text);
            }
        });
    }

    private void fireLoading(String tip) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (NovelListener l : listeners) {
                l.loading(tip);
            }
        });
    }

    private void fireTip(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (NovelListener l : listeners) {
                l.tip(text);
            }
        });
    }

    public void fireSettingsChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (NovelListener l : listeners) {
                l.settingsChanged();
            }
        });
    }

    private static void notify(String message, NotificationType type) {
        try {
            NotificationGroup group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID);
            Notifications.Bus.notify(group.createNotification("摸鱼小说", message, type));
        } catch (Exception e) {
            LOG.warn("通知发送失败: " + message, e);
        }
    }
}
