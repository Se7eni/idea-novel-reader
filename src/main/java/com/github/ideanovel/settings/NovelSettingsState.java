package com.github.ideanovel.settings;

import com.github.ideanovel.model.ReadingProgress;
import com.github.ideanovel.parser.ChapterParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 插件设置与阅读进度，持久化在 ideanovel.xml。
 * 字段保持 public 以便 XmlSerializer 直接存取。
 */
@State(name = "IdeaNovelReaderSettings", storages = @Storage("ideanovel.xml"))
public class NovelSettingsState implements PersistentStateComponent<NovelSettingsState> {

    /** 最大保留的书籍进度条数 */
    private static final int MAX_HISTORY = 50;

    /** 正文字号 */
    public int fontSize = 16;
    /** 行距百分比，160 表示 1.6 倍 */
    public int lineSpacingPercent = 180;
    /** 字体名，留空用系统默认 */
    public String fontFamily = "";

    /** 主题：0 跟随 IDE / 1 护眼绿 / 2 夜间 / 3 羊皮纸 */
    public int themeMode = 0;

    public boolean useRegexChapter = true;
    public String chapterRegex = ChapterParser.DEFAULT_REGEX;
    /** 正则失效时的分块大小 */
    public int chunkSize = 4000;

    public boolean autoScroll = false;
    /** 自动滚动速度，单位：行/秒 */
    public int autoScrollSpeed = 2;

    public boolean showChapterList = true;
    /** 段落首行缩进字符数 */
    public int paragraphIndent = 2;

    /** 工具栏按钮是否用图标（窄工具栏下更省地方） */
    public boolean compactToolbar = true;

    /** 是否启用伪装 */
    public boolean disguiseEnabled = true;
    /** 伪装模式：0 构建日志 / 1 代码 / 2 终端 */
    public int disguiseMode = 0;
    /** 老板键是否直接隐藏整个工具窗，而不是伪装 */
    public boolean bossKeyHideWindow = false;

    public boolean rememberProgress = true;
    /** 网络小说一次最多解析多少章 */
    public int remoteChapterLimit = 50;

    /** 阅读进度历史 */
    public List<ReadingProgress> progress = new ArrayList<>();

    public static NovelSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(NovelSettingsState.class);
    }

    @Override
    public @NotNull NovelSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull NovelSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
        if (progress == null) {
            progress = new ArrayList<>();
        }
        if (fontSize <= 0) {
            fontSize = 16;
        }
        if (lineSpacingPercent < 100) {
            lineSpacingPercent = 180;
        }
    }

    public ThemeMode theme() {
        ThemeMode[] values = ThemeMode.values();
        if (themeMode < 0 || themeMode >= values.length) {
            return ThemeMode.FOLLOW_IDE;
        }
        return values[themeMode];
    }

    public DisguiseMode disguise() {
        DisguiseMode[] values = DisguiseMode.values();
        if (disguiseMode < 0 || disguiseMode >= values.length) {
            return DisguiseMode.BUILD_LOG;
        }
        return values[disguiseMode];
    }

    /** 查找某本书的进度 */
    public ReadingProgress findProgress(String bookId) {
        if (progress == null || bookId == null) {
            return null;
        }
        for (ReadingProgress p : progress) {
            if (bookId.equals(p.bookId)) {
                return p;
            }
        }
        return null;
    }

    /** 保存进度，同名书籍覆盖，超出上限丢掉最旧的 */
    public void saveProgress(ReadingProgress p) {
        if (progress == null) {
            progress = new ArrayList<>();
        }
        p.updatedAt = System.currentTimeMillis();
        progress.removeIf(x -> x.bookId != null && x.bookId.equals(p.bookId));
        progress.add(0, p);
        progress.sort(Comparator.comparingLong((ReadingProgress x) -> x.updatedAt).reversed());
        while (progress.size() > MAX_HISTORY) {
            progress.remove(progress.size() - 1);
        }
    }

    /** 最近读过的书，按时间倒序 */
    public List<ReadingProgress> recentBooks() {
        if (progress == null) {
            return new ArrayList<>();
        }
        List<ReadingProgress> copy = new ArrayList<>(progress);
        copy.sort(Comparator.comparingLong((ReadingProgress x) -> x.updatedAt).reversed());
        return copy;
    }

    /** 阅读主题 */
    public enum ThemeMode {
        FOLLOW_IDE("跟随 IDE 主题"),
        GREEN("护眼绿"),
        DARK("夜间黑"),
        SEPIA("羊皮纸"),
        LIGHT("纯白");

        private final String label;

        ThemeMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 伪装模式 */
    public enum DisguiseMode {
        BUILD_LOG("Maven 构建日志"),
        CODE("Java 源码"),
        TERMINAL("终端输出");

        private final String label;

        DisguiseMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
