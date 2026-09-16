package com.github.ideanovel.ui;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.model.ReadingProgress;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.service.NovelReaderService;
import com.github.ideanovel.settings.NovelSettingsConfigurable;
import com.github.ideanovel.settings.NovelSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

/**
 * 阅读主面板。
 */
public class ReaderPanel extends JPanel implements Disposable, NovelReaderService.NovelListener {

    public static final Key<ReaderPanel> PANEL_KEY = Key.create("IdeaNovel.ReaderPanel");

    private static final String CARD_READER = "reader";
    private static final String CARD_DISGUISE = "disguise";

    private final Project project;
    private final ToolWindow toolWindow;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);

    private final JTextPane textPane = new JTextPane();
    private final JBScrollPane scrollPane = new JBScrollPane(textPane);
    private final ChapterListPanel chapterList = new ChapterListPanel();
    private final JSplitPane splitPane;
    private final JLabel titleLabel = new JLabel("尚未打开小说");
    private final JLabel statusLabel = new JLabel(" ");
    private final DisguisePanel disguisePanel = new DisguisePanel();

    private Timer autoScrollTimer;
    private Timer progressTimer;
    private Timer tipTimer;
    private boolean disguised;
    private boolean autoNextFired;

    private int lastIndex = -1;
    private float lastRatio;

    public ReaderPanel(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;

        setLayout(new BorderLayout(0, 4));
        add(buildToolbar(), BorderLayout.NORTH);

        textPane.setEditable(false);
        textPane.setBorder(BorderFactory.createEmptyBorder(10, 14, 24, 14));

        // Ctrl + 滚轮：在正文区直接缩放字号（不加 Ctrl 时照常滚动）
        textPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                NovelReaderService.getInstance().changeFontSize(e.getWheelRotation() < 0 ? 1 : -1);
                e.consume();
            }
        });

        chapterList.setSelectListener(index -> {
            flushProgress();
            NovelReaderService.getInstance().gotoChapter(index);
        });

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chapterList, scrollPane);
        splitPane.setDividerLocation(180);
        splitPane.setDividerSize(4);

        JPanel readerCard = new JPanel(new BorderLayout(0, 4));
        readerCard.add(titleLabel, BorderLayout.NORTH);
        readerCard.add(splitPane, BorderLayout.CENTER);

        cardPanel.add(readerCard, CARD_READER);
        cardPanel.add(disguisePanel, CARD_DISGUISE);
        add(cardPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        NovelReaderService.getInstance().addListener(this);
        project.putUserData(PANEL_KEY, this);

        applyStyle();
        applyChapterListVisibility();

        // 滚动位置采样，切章或关闭时落盘
        progressTimer = new Timer(2000, e -> lastRatio = currentRatio());
        progressTimer.start();

        showWelcome();
    }

    // ---------------- 界面组装 ----------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(smallButton("打开", "选择一个本地 txt 小说", this::openLocal));
        bar.add(smallButton("网络", "输入网址打开网络小说", this::openRemote));
        bar.add(smallButton("书架", "最近读过的小说", this::showShelf));
        bar.add(smallButton("目录", "显示/隐藏章节目录", this::toggleChapterList));
        bar.add(smallButton("◀", "上一章", () -> {
            flushProgress();
            NovelReaderService.getInstance().prevChapter();
        }));
        bar.add(smallButton("▶", "下一章", () -> {
            flushProgress();
            NovelReaderService.getInstance().nextChapter();
        }));
        bar.add(smallButton("自动", "自动滚动翻页", this::toggleAutoScroll));
        bar.add(smallButton("A-", "缩小字号（Ctrl+滚轮 / Ctrl+Alt+Shift+-）",
                () -> NovelReaderService.getInstance().changeFontSize(-2)));
        bar.add(smallButton("A+", "放大字号（Ctrl+滚轮 / Ctrl+Alt+Shift+=）",
                () -> NovelReaderService.getInstance().changeFontSize(2)));
        bar.add(smallButton("设置", "打开插件设置", this::openSettings));
        bar.add(smallButton("隐身", "老板键：伪装成工作界面（Ctrl+Alt+Shift+X）", this::toggleDisguise));
        return bar;
    }

    private JButton smallButton(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        // 收窄边距，工具栏按钮不至于太占地方
        b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    private void showWelcome() {
        textPane.setText("还没打开小说。\n\n" +
                "点上面的「打开」选一个本地 txt，或点「网络」输入小说目录页网址。\n" +
                "快捷键 Ctrl+Alt+Shift+X 是老板键，一键切换成工作界面。");
        applyStyle();
    }

    // ---------------- 渲染 ----------------

    private void setChapterText(String text) {
        textPane.setText(text == null ? "" : text);
        applyStyle();
        SwingUtilities.invokeLater(() -> {
            textPane.setCaretPosition(0);
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(0);
        });
    }

    /** 把字号、行距、配色、缩进应用上去 */
    private void applyStyle() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        int size = s == null ? 16 : Math.max(9, s.fontSize);
        float spacing = (s == null ? 180 : Math.max(100, s.lineSpacingPercent)) / 100f;
        String family = (s == null || s.fontFamily == null) ? "" : s.fontFamily.trim();
        int indent = s == null ? 2 : Math.max(0, s.paragraphIndent);

        Color[] colors = themeColors();

        Font font = family.isEmpty()
                ? new Font(Font.DIALOG, Font.PLAIN, size)
                : new Font(family, Font.PLAIN, size);

        MutableAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, font.getFamily());
        StyleConstants.setFontSize(attrs, size);
        StyleConstants.setLineSpacing(attrs, spacing - 1f);
        StyleConstants.setForeground(attrs, colors[1]);
        StyleConstants.setFirstLineIndent(attrs, indent * size);

        StyledDocument doc = textPane.getStyledDocument();
        doc.setParagraphAttributes(0, Math.max(1, doc.getLength()), attrs, false);

        textPane.setBackground(colors[0]);
        textPane.setCaretColor(colors[1]);
        textPane.setSelectedTextColor(colors[1]);
        scrollPane.getViewport().setBackground(colors[0]);
        scrollPane.setBackground(colors[0]);
        chapterList.setBackground(colors[0]);
    }

    private Color[] themeColors() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        NovelSettingsState.ThemeMode mode = s == null ? NovelSettingsState.ThemeMode.FOLLOW_IDE : s.theme();
        switch (mode) {
            case GREEN:
                return new Color[]{new Color(0xC7, 0xED, 0xCC), new Color(0x2E, 0x3B, 0x2E)};
            case DARK:
                return new Color[]{new Color(0x1E, 0x1E, 0x1E), new Color(0xD4, 0xD4, 0xD4)};
            case SEPIA:
                return new Color[]{new Color(0xF4, 0xEC, 0xD8), new Color(0x5B, 0x46, 0x36)};
            case LIGHT:
                return new Color[]{Color.WHITE, new Color(0x1A, 0x1A, 0x1A)};
            case FOLLOW_IDE:
            default:
                try {
                    EditorColorsManager manager = EditorColorsManager.getInstance();
                    if (manager != null) {
                        Color bg = manager.getGlobalScheme().getDefaultBackground();
                        Color fg = manager.getGlobalScheme().getDefaultForeground();
                        if (bg != null && fg != null) {
                            return new Color[]{bg, fg};
                        }
                    }
                } catch (Exception ignored) {
                    // 拿不到 IDE 配色就退回默认
                }
                return new Color[]{new Color(0xF7, 0xF7, 0xF7), new Color(0x2B, 0x2B, 0x2B)};
        }
    }

    private void applyChapterListVisibility() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        boolean visible = s == null || s.showChapterList;
        chapterList.setVisible(visible);
        splitPane.setEnabled(visible);
        if (!visible) {
            splitPane.setDividerLocation(0);
        } else if (splitPane.getDividerLocation() < 40) {
            splitPane.setDividerLocation(180);
        }
        revalidate();
        repaint();
    }

    // ---------------- 滚动与进度 ----------------

    private float currentRatio() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int max = bar.getMaximum() - bar.getVisibleAmount();
        if (max <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) bar.getValue() / max));
    }

    private void restoreRatio(float ratio) {
        if (ratio <= 0.001f) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            int max = bar.getMaximum() - bar.getVisibleAmount();
            if (max > 0) {
                bar.setValue((int) (max * ratio));
            }
        });
    }

    /** 把当前滚动位置写进进度 */
    public void flushProgress() {
        if (lastIndex >= 0) {
            NovelReaderService.getInstance().saveProgressAt(lastIndex, lastRatio);
        }
    }

    private void toggleAutoScroll() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null) {
            return;
        }
        s.autoScroll = !s.autoScroll;
        syncAutoScroll();
    }

    private void syncAutoScroll() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        boolean on = s != null && s.autoScroll;
        if (on && autoScrollTimer == null) {
            int speed = Math.max(1, s.autoScrollSpeed);
            autoScrollTimer = new Timer(60, e -> scrollTick(speed));
            autoScrollTimer.start();
        } else if (!on && autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
    }

    private void scrollTick(int speed) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int max = bar.getMaximum() - bar.getVisibleAmount();
        if (max <= 0) {
            return;
        }
        NovelSettingsState s = NovelSettingsState.getInstance();
        int size = s == null ? 16 : s.fontSize;
        float spacing = (s == null ? 180 : s.lineSpacingPercent) / 100f;
        int lineHeight = Math.max(8, (int) (size * spacing));
        int delta = Math.max(1, (int) (speed * lineHeight * 0.06));

        int next = bar.getValue() + delta;
        if (next >= max) {
            bar.setValue(max);
            if (!autoNextFired) {
                autoNextFired = true;
                flushProgress();
                NovelReaderService.getInstance().nextChapter();
            }
        } else {
            autoNextFired = false;
            bar.setValue(next);
        }
    }

    // ---------------- 工具条动作 ----------------

    private void openLocal() {
        FileChooserDescriptorHolder holder = new FileChooserDescriptorHolder();
        VirtualFile file = FileChooser.chooseFile(holder.descriptor(), project, null);
        if (file != null) {
            NovelReaderService.getInstance().openLocal(file.getPath());
        }
    }

    /** 单独包一层，避免构造 FileChooserDescriptor 的逻辑散落在面板里 */
    private static final class FileChooserDescriptorHolder {
        com.intellij.openapi.fileChooser.FileChooserDescriptor descriptor() {
            com.intellij.openapi.fileChooser.FileChooserDescriptor d =
                    FileChooserDescriptorFactory.createSingleFileDescriptor("txt");
            d.setTitle("选择小说文件");
            return d;
        }
    }

    private void openRemote() {
        String url = Messages.showInputDialog(project,
                "输入小说目录页网址：", "打开网络小说", Messages.getQuestionIcon());
        if (url != null && !url.trim().isEmpty()) {
            NovelReaderService.getInstance().openRemote(url.trim());
        }
    }

    private void showShelf() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null) {
            return;
        }
        List<ReadingProgress> books = s.recentBooks();
        JPopupMenu menu = new JPopupMenu();
        if (books.isEmpty()) {
            menu.add(new JMenuItem("（还没有阅读记录）"));
        } else {
            for (ReadingProgress p : books) {
                String name = (p.title == null || p.title.isEmpty()) ? p.location : p.title;
                JMenuItem item = new JMenuItem("第 " + (p.chapterIndex + 1) + " 章 · " + name);
                item.setToolTipText(p.location);
                item.addActionListener(e -> {
                    if (p.safeType() == SourceType.LOCAL) {
                        NovelReaderService.getInstance().openLocal(p.location);
                    } else {
                        NovelReaderService.getInstance().openRemote(p.location);
                    }
                });
                menu.add(item);
            }
        }
        menu.show(this, 0, 24);
    }

    private void toggleChapterList() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null) {
            return;
        }
        s.showChapterList = !s.showChapterList;
        applyChapterListVisibility();
    }

    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, NovelSettingsConfigurable.class);
    }

    /** 老板键：在阅读区和伪装界面之间切换 */
    public void toggleDisguise() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s != null && s.bossKeyHideWindow && toolWindow != null) {
            if (toolWindow.isVisible()) {
                flushProgress();
                toolWindow.hide(null);
            } else {
                toolWindow.show(null);
            }
            return;
        }
        if (disguised) {
            disguised = false;
            disguisePanel.stop();
            cardLayout.show(cardPanel, CARD_READER);
        } else {
            flushProgress();
            disguised = true;
            disguisePanel.rebuild();
            disguisePanel.start();
            cardLayout.show(cardPanel, CARD_DISGUISE);
        }
    }

    public boolean isDisguised() {
        return disguised;
    }

    // ---------------- 服务回调 ----------------

    @Override
    public void bookChanged(Book book) {
        chapterList.setChapters(book.getChapters());
        titleLabel.setText(book.getTitle() + "  [" + book.getType().displayName() + "]");
        applyChapterListVisibility();
        updateStatus();
    }

    @Override
    public void chapterChanged(int index, String text) {
        if (lastIndex >= 0 && lastIndex != index) {
            NovelReaderService.getInstance().saveProgressAt(lastIndex, lastRatio);
        }
        lastIndex = index;
        lastRatio = 0f;
        autoNextFired = false;

        setChapterText(text);
        chapterList.selectChapter(index);
        updateStatus();
        restoreRatio(NovelReaderService.getInstance().savedChapterRatio());
    }

    @Override
    public void loading(String tip) {
        statusLabel.setText(tip == null ? "" : tip);
    }

    @Override
    public void tip(String text) {
        statusLabel.setText(text);
        if (tipTimer != null) {
            tipTimer.stop();
        }
        tipTimer = new Timer(2500, e -> {
            updateStatus();
            tipTimer = null;
        });
        tipTimer.setRepeats(false);
        tipTimer.start();
    }

    @Override
    public void settingsChanged() {
        applyStyle();
        applyChapterListVisibility();
        syncAutoScroll();
        updateStatus();
        if (disguised) {
            disguisePanel.rebuild();
        }
    }

    private void updateStatus() {
        NovelReaderService service = NovelReaderService.getInstance();
        Book book = service.getBook();
        if (book == null) {
            statusLabel.setText(" ");
            return;
        }
        int idx = service.getChapterIndex();
        int total = book.getChapters().size();
        String text = String.format("第 %d / %d 章 · %s · 本章约 %,d 字",
                idx + 1, Math.max(total, 1), service.currentChapterTitle(),
                service.currentText().length());
        statusLabel.setText(text);
        statusLabel.setToolTipText(text);
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void dispose() {
        NovelReaderService.getInstance().removeListener(this);
        if (autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
        if (progressTimer != null) {
            progressTimer.stop();
            progressTimer = null;
        }
        if (tipTimer != null) {
            tipTimer.stop();
            tipTimer = null;
        }
        disguisePanel.stop();
        flushProgress();
        if (!project.isDisposed()) {
            project.putUserData(PANEL_KEY, null);
        }
    }
}
