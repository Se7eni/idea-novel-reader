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
import static com.github.ideanovel.ui.ReaderIcons.autoScrollIcon;
import static com.github.ideanovel.ui.ReaderIcons.arrowIcon;
import static com.github.ideanovel.ui.ReaderIcons.fontIcon;
import static com.github.ideanovel.ui.ReaderIcons.gearIcon;
import static com.github.ideanovel.ui.ReaderIcons.maskIcon;
import static com.github.ideanovel.ui.ReaderIcons.pauseIcon;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;

/**
 * 阅读主面板。
 */
public class ReaderPanel extends JPanel implements Disposable, NovelReaderService.NovelListener {

    public static final Key<ReaderPanel> PANEL_KEY = Key.create("IdeaNovel.ReaderPanel");

    private static final String CARD_READER = "reader";
    private static final String CARD_DISGUISE = "disguise";
    private static final String CARD_SOURCE = "source";

    private final Project project;
    private final ToolWindow toolWindow;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    /** 非 final：切换「图标/文字」工具栏模式时会整体重建 */
    private JPanel toolbar;
    /** 自动滚动按钮：滚动中要换成暂停图标，所以得留个引用按状态刷新 */
    private JButton autoScrollButton;

    private final JTextPane textPane = new JTextPane();
    private final JBScrollPane scrollPane = new JBScrollPane(textPane);
    private final ChapterListPanel chapterList = new ChapterListPanel();
    private final JSplitPane splitPane;
    private final JLabel titleLabel = new JLabel("尚未打开小说");
    private final JLabel statusLabel = new JLabel(" ");
    private final DisguisePanel disguisePanel = new DisguisePanel();
    /** 源码伪装：正文藏在 Javadoc 注释里，伪装的同时还能继续读 */
    private final SourceDisguisePanel sourceDisguisePanel = new SourceDisguisePanel();

    private Timer autoScrollTimer;
    private Timer progressTimer;
    private Timer tipTimer;
    private boolean disguised;
    private boolean autoNextFired;
    /** 上一次布局时的面板宽度，用来判断需不需要重排工具栏 */
    private int lastWidth;

    private int lastIndex = -1;
    private float lastRatio;

    public ReaderPanel(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;

        setLayout(new BorderLayout(0, 4));
        toolbar = buildToolbar();
        add(toolbar, BorderLayout.NORTH);

        textPane.setEditable(false);
        textPane.setBorder(BorderFactory.createEmptyBorder(10, 14, 24, 14));

        // Ctrl + 滚轮：在正文区直接缩放字号。
        // 注意这里必须手动把事件转交给外层滚动容器，原因见 handleWheel 的注释。
        textPane.addMouseWheelListener(this::handleWheel);

        chapterList.setSelectListener(index -> {
            flushProgress();
            NovelReaderService.getInstance().gotoChapter(index);
        });
        // 搜索时显示「匹配 x / y 章」，否则用户不知道是全书就这么多章还是被过滤了
        chapterList.setFilterListener(summary -> {
            if (summary == null || summary.isEmpty()) {
                updateStatus();
            } else {
                statusLabel.setText(summary);
            }
        });

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chapterList, scrollPane);
        splitPane.setDividerLocation(180);
        splitPane.setDividerSize(4);

        JPanel readerCard = new JPanel(new BorderLayout(0, 4));
        readerCard.add(titleLabel, BorderLayout.NORTH);
        readerCard.add(splitPane, BorderLayout.CENTER);

        cardPanel.add(readerCard, CARD_READER);
        cardPanel.add(disguisePanel, CARD_DISGUISE);
        cardPanel.add(sourceDisguisePanel, CARD_SOURCE);
        // 注意：这里不能再给 cardPanel 套一层 JScrollPane。
        // 外层滚动容器会把内层按首选尺寸撑开，正文自己的滚动条就没了滚动范围，
        // 结果滚轮事件冒泡到外层、setValue(0) 也变成空操作（字滚不动、切章不回顶部）。
        add(cardPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        NovelReaderService.getInstance().addListener(this);
        project.putUserData(PANEL_KEY, this);

        // 宽度变了就重排工具栏（让它多占一行或收回一行）。
        // 这里用 ComponentListener 而不是重写 doLayout —— 在布局过程中调 revalidate()
        // 相当于让 Swing 重新进入一遍布局，容易触发重排抖动。
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = ReaderPanel.this.getWidth();
                if (w > 0 && w != lastWidth) {
                    lastWidth = w;
                    toolbar.revalidate();
                    toolbar.repaint();
                }
            }
        });

        applyStyle();
        applyChapterListVisibility();

        // 滚动位置采样，切章或关闭时落盘
        progressTimer = new Timer(2000, e -> lastRatio = currentRatio());
        progressTimer.start();

        showWelcome();
    }


    // ---------------- 界面组装 ----------------

    /**
     * 处理正文区的滚轮。
     *
     * <p><b>Swing 的坑</b>：只要给正文组件注册了 MouseWheelListener，滚轮事件就会被
     * 重定向到正文并<b>就地截止</b>，不再往上传递到外层滚动容器，默认的滚动逻辑会整体失效。
     * 所以非 Ctrl 的情况必须在这里把滚动补回来，否则看起来就是「鼠标滚轮完全没反应」。
     *
     * <p>已用 Robot 注入真实滚轮事件实测：正文加了监听不处理时位移 0，补回后位移 375。
     *
     * <p>做法是「先转发、不行再自己滚」：优先把事件交给滚动容器（保留标准的
     * 行/页滚动语义），万一滚动容器没把事情办了（比如用 JBScrollPane 时其行为
     * 依赖 IDE 运行时，脱离 IDE 难以验证），再用 {@link #wheelScroll} 兜底，
     * 保证滚轮一定能动。
     */
    private void handleWheel(java.awt.event.MouseWheelEvent e) {
        if (e.isControlDown()) {
            NovelReaderService.getInstance().changeFontSize(e.getWheelRotation() < 0 ? 1 : -1);
            e.consume();
            return;
        }

        java.awt.Point before = scrollPane.getViewport().getViewPosition();
        scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(textPane, e, scrollPane));
        java.awt.Point after = scrollPane.getViewport().getViewPosition();

        // 转发没起到作用时自己滚，避免依赖滚动容器内部实现
        if (before.equals(after)) {
            wheelScroll(e);
        }
    }

    /** 手动滚动一行/一页的量，逻辑与 BasicScrollPaneUI 的滚轮处理保持一致 */
    private void wheelScroll(java.awt.event.MouseWheelEvent e) {
        boolean horizontal = e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
        JScrollBar bar = horizontal
                ? scrollPane.getHorizontalScrollBar()
                : scrollPane.getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) {
            return;
        }
        int orientation = horizontal ? SwingConstants.HORIZONTAL : SwingConstants.VERTICAL;
        int direction = e.getWheelRotation() < 0 ? -1 : 1;
        // 注意：可视矩形要传文本组件自己的坐标系（viewport.getViewRect() 正是这个坐标系）
        java.awt.Rectangle visible = scrollPane.getViewport().getViewRect();

        int unit;
        if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            // 用 JTextPane 自身的 Scrollable 实现取行高。
            // 不能用 scrollBar.getUnitIncrement() —— 实测它返回 1（像素），滚起来等于不动。
            // 也不能调 viewport 上的同名方法：JDK 25 起 JViewport 不再实现 Scrollable。
            unit = textPane.getScrollableUnitIncrement(visible, orientation, direction);
            unit *= e.getUnitsToScroll();
        } else {
            unit = (e.getWheelRotation() < 0 ? -1 : 1)
                    * textPane.getScrollableBlockIncrement(visible, orientation, direction);
        }
        if (unit == 0) {
            return;
        }

        int old = bar.getValue();
        int next = Math.max(0, Math.min(old + unit, bar.getMaximum() - bar.getModel().getExtent()));
        if (next != old) {
            bar.setValue(next);
        }
        e.consume();
    }

    /** 工具栏：第一行是导航，第二行是阅读控制，放不下时自动折行 */
    private JPanel buildToolbar() {
        // 窄工具栏放不下所有按钮，所以拆成两行：
        // 第一行是导航（打开/网络/书架/目录），第二行是阅读控制（翻章、字号、设置、隐身）。
        // 再窄也只会换行，不会把右侧按钮裁掉。
        JPanel bar = new JPanel();
        bar.setLayout(new WrapLayout(FlowLayout.LEFT, 4, 2));
        boolean compact = isCompactToolbar();
        bar.add(smallButton("打开", "选择一个本地 txt 小说", this::openLocal));
        bar.add(smallButton("网络", "输入网址打开网络小说", this::openRemote));
        bar.add(smallButton("书架", "最近读过的小说", this::showShelf));
        bar.add(smallButton("目录", "显示/隐藏章节目录", this::toggleChapterList));
        bar.add(separator());
        bar.add(commandButton(compact, "上一章", "◀", arrowIcon(false), () -> {
            flushProgress();
            NovelReaderService.getInstance().prevChapter();
        }));
        bar.add(commandButton(compact, "下一章", "▶", arrowIcon(true), () -> {
            flushProgress();
            NovelReaderService.getInstance().nextChapter();
        }));
        // 自动滚动留了引用：滚动过程中要把图标切成暂停，点完一看就知道当前状态
        autoScrollButton = commandButton(compact, "开始自动滚动（向下）", "自动",
                autoScrollIcon(), this::toggleAutoScroll);
        bar.add(autoScrollButton);
        updateAutoScrollButton();
        bar.add(commandButton(compact, "缩小字号（Ctrl+滚轮 / Ctrl+Alt+Shift+-）", "A-",
                fontIcon(true), () -> NovelReaderService.getInstance().changeFontSize(-2)));
        bar.add(commandButton(compact, "放大字号（Ctrl+滚轮 / Ctrl+Alt+Shift+=）", "A+",
                fontIcon(false), () -> NovelReaderService.getInstance().changeFontSize(2)));
        bar.add(commandButton(compact, "打开设置", "设置", gearIcon(), this::openSettings));
        bar.add(commandButton(compact, "老板键：伪装成工作界面（Ctrl+Alt+Shift+X）", "隐身",
                maskIcon(), this::toggleDisguise));
        // 工具栏被挤窄时右键可唤出全部命令，不依赖按钮是否可见
        installToolbarContextMenu(bar);
        return bar;
    }

    private boolean isCompactToolbar() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        return s == null || s.compactToolbar;
    }

    /** 图标模式下用纯图标，否则退回文字按钮 */
    private JButton commandButton(boolean compact, String tooltip, String text, Icon icon, Runnable action) {
        return compact ? iconButton(tooltip, icon, action) : smallButton(text, tooltip, action);
    }

    /** 工具栏右键菜单：无论窗口多窄，所有功能都能点到 */
    private void installToolbarContextMenu(JPanel bar) {
        bar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                JPopupMenu menu = new JPopupMenu();
                menu.add(menuItem("打开本地小说...", ReaderPanel.this::openLocal));
                menu.add(menuItem("打开网络小说...", ReaderPanel.this::openRemote));
                menu.add(menuItem("最近书架", ReaderPanel.this::showShelf));
                menu.add(menuItem("显示/隐藏章节目录", ReaderPanel.this::toggleChapterList));
                menu.addSeparator();
                menu.add(menuItem("上一章", () -> {
                    flushProgress();
                    NovelReaderService.getInstance().prevChapter();
                }));
                menu.add(menuItem("下一章", () -> {
                    flushProgress();
                    NovelReaderService.getInstance().nextChapter();
                }));
                menu.add(menuItem("自动滚动开关", ReaderPanel.this::toggleAutoScroll));
                menu.addSeparator();
                menu.add(menuItem("放大字号", () -> NovelReaderService.getInstance().changeFontSize(2)));
                menu.add(menuItem("缩小字号", () -> NovelReaderService.getInstance().changeFontSize(-2)));
                menu.add(menuItem("打开设置", ReaderPanel.this::openSettings));
                menu.add(menuItem("老板键（隐身/还原）", ReaderPanel.this::toggleDisguise));
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** 窄竖线分隔符，让导航组和工具组一眼能分开 */
    private JPanel separator() {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(1, 16));
        p.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0,
                javax.swing.UIManager.getColor("Component.borderColor") == null
                        ? new Color(0x60, 0x60, 0x60)
                        : javax.swing.UIManager.getColor("Component.borderColor")));
        return p;
    }

    private JButton smallButton(String text, String tooltip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        // 收窄边距，工具栏按钮不至于太占地方
        b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** 纯图标按钮：比文字按钮窄一半以上，是窄工具栏下最省地方的做法 */
    private JButton iconButton(String tooltip, Icon icon, Runnable action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setMargin(new java.awt.Insets(2, 4, 2, 4));
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(26, 24));
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * 会换行的 FlowLayout。JDK 自带的 FlowLayout 放不下时会直接把组件裁掉，
     * 在这么窄的工具栏里就会丢按钮，所以这里自己算高度。
     */
    private static final class WrapLayout extends FlowLayout {

        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container target) {
            Dimension d = layoutSize(target, false);
            d.width -= (getHgap() + 1);
            return d;
        }

        private Dimension layoutSize(java.awt.Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth <= 0) {
                    java.awt.Container parent = target.getParent();
                    targetWidth = parent == null ? 0 : parent.getSize().width;
                }
                if (targetWidth <= 0) {
                    targetWidth = Integer.MAX_VALUE;
                }
                java.awt.Insets insets = target.getInsets();
                int maxWidth = targetWidth - (insets.left + insets.right + getHgap() * 2);

                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                int members = target.getComponentCount();
                for (int i = 0; i < members; i++) {
                    java.awt.Component m = target.getComponent(i);
                    if (!m.isVisible()) {
                        continue;
                    }
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth != 0) {
                        rowWidth += getHgap();
                    }
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);

                dim.width += insets.left + insets.right + getHgap() * 2;
                dim.height += insets.top + insets.bottom + getVgap() * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
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
        scrollToStart();
        // 新文本刚灌进去时还没走完布局，滚动条的 max 可能还是旧值，
        // 所以等 Swing 排完版再拉一次，确保真的停在第一行。
        SwingUtilities.invokeLater(this::scrollToStart);
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

    /** 把正文拉到本页开头 */
    private void scrollToStart() {
        scrollToRatio(0f);
    }

    /**
     * 按百分比定位正文。同时设 viewPosition 和滚动条 value——只用 setValue 时，
     * 若目标值等于当前值或范围还没刷新，JScrollBar 可能直接忽略。
     */
    private void scrollToRatio(float ratio) {
        int y = ratio <= 0.001f ? 0 : (int) (maxScroll() * ratio);
        scrollPane.getViewport().setViewPosition(new java.awt.Point(0, y));
        scrollPane.getVerticalScrollBar().setValue(y);
        if (ratio <= 0.001f) {
            textPane.setCaretPosition(0);
        }
    }

    private int maxScroll() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        return Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
    }

    private void restoreRatio(float ratio) {
        scrollToRatio(ratio);
        // 同上，等排版完成再校准一次，避免滚到半途被后面的重排拉回去
        SwingUtilities.invokeLater(() -> scrollToRatio(ratio));
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
        updateAutoScrollButton();
    }

    /** 让按钮自己说出当前状态：滚动中显示暂停，停下后变回向下箭头 */
    private void updateAutoScrollButton() {
        if (autoScrollButton == null) {
            return;
        }
        NovelSettingsState s = NovelSettingsState.getInstance();
        boolean on = s != null && s.autoScroll;
        String tip = on ? "停止自动滚动" : "开始自动滚动（向下）";
        if (isCompactToolbar()) {
            autoScrollButton.setIcon(on ? pauseIcon() : autoScrollIcon());
            autoScrollButton.setText("");
        } else {
            autoScrollButton.setIcon(null);
            autoScrollButton.setText(on ? "停止" : "自动");
        }
        autoScrollButton.setToolTipText(tip);
        autoScrollButton.repaint();
    }

    private void scrollTick(int speed) {
        // 源码伪装态下正文是不可见的，让它继续滚会一路滚到底然后自动翻章，
        // 表现成"假界面自己在翻页"。这里直接不滚。
        if (disguised && isSourceDisguise()) {
            return;
        }
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
            if (isSourceDisguise()) {
                // 源码模式：正文进注释块，位置对齐到刚才读的地方
                sourceDisguisePanel.rebuild();
                sourceDisguisePanel.scrollToRatio(currentRatio());
                cardLayout.show(cardPanel, CARD_SOURCE);
            } else {
                disguisePanel.rebuild();
                disguisePanel.start();
                cardLayout.show(cardPanel, CARD_DISGUISE);
            }
        }
    }

    /** 当前是不是选了「源码（正文作注释）」这个伪装模式 */
    private boolean isSourceDisguise() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        return s != null && s.disguise() == NovelSettingsState.DisguiseMode.SOURCE_DOC;
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
        // 源码伪装态下翻了章，注释块内容得跟着换
        if (disguised && isSourceDisguise()) {
            sourceDisguisePanel.rebuild();
        }

        // 只有「接着上次读」才是有位置的；正常翻下一章时这里拿到 0，会停在开头
        float saved = NovelReaderService.getInstance().savedChapterRatio();
        if (saved > 0.001f) {
            restoreRatio(saved);
        }
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
        rebuildToolbarIfNeeded();
        syncAutoScroll();
        updateStatus();
        if (disguised) {
            if (isSourceDisguise()) {
                // 伪装中把模式改成了源码：换卡片重渲染
                sourceDisguisePanel.rebuild();
                cardLayout.show(cardPanel, CARD_SOURCE);
            } else {
                disguisePanel.rebuild();
                cardLayout.show(cardPanel, CARD_DISGUISE);
            }
        }
    }

    /** 「工具栏用图标按钮」切换后需要重建工具栏，其余设置项直接生效即可 */
    private void rebuildToolbarIfNeeded() {
        if (toolbar == null) {
            return;
        }
        boolean compact = isCompactToolbar();
        // 图标模式按钮没有文字、文字模式有；两者不一致才重建，避免调字号时反复重建
        boolean iconMode = commandButtonIsIconMode();
        if (iconMode != compact) {
            remove(toolbar);
            toolbar = buildToolbar();
            add(toolbar, BorderLayout.NORTH);
            revalidate();
            repaint();
        }
    }

    /** 从工具栏里任意一个命令按钮反推当前是不是图标模式 */
    private boolean commandButtonIsIconMode() {
        for (java.awt.Component c : toolbar.getComponents()) {
            if (c instanceof JButton) {
                String text = ((JButton) c).getText();
                // 「打开」这类文字按钮始终有文字，图标按钮的文字为空
                if (text == null || text.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
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
