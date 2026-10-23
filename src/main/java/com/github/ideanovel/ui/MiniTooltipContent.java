package com.github.ideanovel.ui;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码提示框的内容：贴在代码中间的一小块浮层，一次显示一小截正文和 (n/N) 段进度。
 *
 * <p>和 1.0.9 的源码大窗是两种形态：大窗像「快速文档」，看得多但动静大；
 * 这个像 IDE 的悬停提示，一次瞟一兩句，几乎不挡代码。
 * 长相就是平台ToolTip：小、无边框标题、文字直接铺开，进度数字比正文淡一档。
 *
 * <p>翻段的粒度是「段」不是「章」：滚轮/方向键翻段，翻到章尾自动进入下一章的第一段，
 * 章首往后翻自动回到上一章最后一段——对使用者来说就是一条连续的文字流。
 *
 * <p>这个类只用标准 Swing，不碰 IntelliJ 平台类（理由同大窗：
 * 宿主 JBPopup 离线创建不了，内容和宿主必须分家才能渲染验证）。
 */
public class MiniTooltipContent extends JPanel {

    /** 回调宿主（CodePopupReader）接阅读服务 */
    public interface Nav {
        void close();

        void nextChapter();

        void prevChapter();

        /** 请求切换自动翻段（开/关）。宿主决定用什么间隔，通常是上次填的秒数 */
        void toggleAutoAdvance();

        /** 请求设置间隔秒数并开始自动翻段。宿主弹输入框 */
        void changeAutoSeconds();
    }

    public enum ChunkAction {
        NONE, CLOSE, NEXT_CHUNK, PREV_CHUNK, NEXT_CHAPTER, PREV_CHAPTER, TOGGLE_AUTO, CHANGE_AUTO
    }

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;

    /** 键位表 + {@link #resolveKey} 的唯一数据源，和实际绑定不会漂移 */
    private static final Object[][] KEY_TABLE = {
            {KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ChunkAction.CLOSE},
            {KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), ChunkAction.NEXT_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), ChunkAction.NEXT_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), ChunkAction.NEXT_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), ChunkAction.PREV_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), ChunkAction.PREV_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), ChunkAction.PREV_CHUNK},
            {KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, CTRL), ChunkAction.NEXT_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, CTRL), ChunkAction.NEXT_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_UP, CTRL), ChunkAction.PREV_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, CTRL), ChunkAction.PREV_CHAPTER},
            // 空格切换自动翻段：手动看累了按一下就开始自己走，再按一下停
            {KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), ChunkAction.TOGGLE_AUTO},
            // S 设置间隔秒数并开始自动翻段（挂机阅读的入口）
            {KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), ChunkAction.CHANGE_AUTO},
    };

    /** 弹窗宽度上限（像素）。单行文字再长也不超过这个，超出才折行 */
    private static final int MAX_WIDTH = 600;

    private final JTextPane textPane = new JTextPane();
    private final javax.swing.JLabel headerLabel = new javax.swing.JLabel(" ");

    private Nav nav = new Nav() {
        @Override
        public void close() {
        }

        @Override
        public void nextChapter() {
        }

        @Override
        public void prevChapter() {
        }

        @Override
        public void toggleAutoAdvance() {
        }

        @Override
        public void changeAutoSeconds() {
        }
    };

    private int fontSize = 13;
    private List<String> chunks = new ArrayList<>();
    private int chunkIndex;
    private int chapterIndex;
    private String chapterTitle = "";

    /** 宿主在 chapterChanged 前指定的「从第几段开始」（记忆位置恢复用），-1 表示从头/末尾 */
    private int pendingChunk = -1;

    /** 定时自动翻段的计时器；null 表示手动模式 */
    private javax.swing.Timer autoTimer;

    /** 尺寸变化时回调宿主，让 JBPopup 重新 pack。宿主 set 进来，内容不需要知道 JBPopup 长啥样 */
    private java.util.function.Consumer<Dimension> resizeListener;

    public MiniTooltipContent() {
        setLayout(new java.awt.BorderLayout());
        // 顶部标题栏：章节名 + 自动翻段状态，让弹窗一眼知道在读哪、是不是自动在走
        headerLabel.setOpaque(false);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(3, 11, 1, 11));
        headerLabel.setFont(headerLabel.getFont().deriveFont(10f));
        add(headerLabel, java.awt.BorderLayout.NORTH);

        textPane.setEditable(false);
        textPane.setFocusable(false);
        textPane.setOpaque(false);
        textPane.addMouseWheelListener(this::handleWheel);
        add(textPane, java.awt.BorderLayout.CENTER);

        // 自己要能拿焦点，按键才落到 WHEN_IN_FOCUSED_WINDOW 绑定上
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setBorder(BorderFactory.createEmptyBorder(0, 11, 9, 11));
        installKeyBindings();
        applyFont();
        applyThemeColors();
    }

    // ---------------- 内容 ----------------

    /**
     * 塞入一章。jumpToEnd 为 true 时直接落在最后一段
     * （从第一章往前翻越章时，用户期望看到上一章的结尾，而不是开头）。
     */
    public void setChapter(String body, int chapterIndexOf, boolean jumpToEnd) {
        this.chapterIndex = Math.max(0, chapterIndexOf);
        chunks = ChapterChunks.split(body);
        if (pendingChunk >= 0 && pendingChunk < chunks.size()) {
            chunkIndex = pendingChunk;
        } else {
            chunkIndex = jumpToEnd ? Math.max(0, chunks.size() - 1) : 0;
        }
        pendingChunk = -1;
        render();
    }

    /** 指定下次 setChapter 从第几段开始（记忆位置恢复）。只生效一次 */
    public void setPendingChunk(int index) {
        this.pendingChunk = index;
    }

    /** 章节标题（供顶部标题栏显示）。宿主在 chapterChanged 时顺带设置 */
    public void setChapterTitle(String title) {
        this.chapterTitle = title == null ? "" : title;
        updateHeader();
    }

    /** 没书可读时的占位文案 */
    public void setPlaceholder(String message) {
        chunks = new ArrayList<>();
        chunks.add(message == null ? "" : message);
        chunkIndex = 0;
        chapterIndex = 0;
        render();
    }

    public void nextChunk() {
        if (chunkIndex < chunks.size() - 1) {
            chunkIndex++;
            render();
        } else {
            // 章尾往后 → 下一章第一段（chapterChanged 回来后 setChapter 从 0 开始，正好）
            nav.nextChapter();
        }
    }

    public void prevChunk() {
        if (chunkIndex > 0) {
            chunkIndex--;
            render();
        } else {
            // 章首往前 → 上一章最后一段，由宿主在 chapterChanged 时传 jumpToEnd=true
            nav.prevChapter();
        }
    }

    /** 当前显示的正文（不含进度数字），探针用 */
    public String bodyText() {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            String text = doc.getText(0, doc.getLength());
            int cut = text.lastIndexOf('(');
            return cut > 0 ? text.substring(0, cut).stripTrailing() : text;
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** 含进度尾巴的完整文本，探针比对用 */
    public String fullText() {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** 顶部标题栏文字，探针用 */
    public String headerText() {
        return headerLabel.getText();
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int chapterIndex() {
        return chapterIndex;
    }

    public JTextPane textPane() {
        return textPane;
    }

    public void setNav(Nav nav) {
        if (nav != null) {
            this.nav = nav;
        }
    }

    /** 内容尺寸变化时回调（宿主用来让 JBPopup 重新 pack） */
    public void setResizeListener(java.util.function.Consumer<Dimension> listener) {
        this.resizeListener = listener;
    }

    /** 顶部标题栏：章节名 + 自动状态。标题通常已含「第N章」，不再重复加前缀 */
    private void updateHeader() {
        if (chapterTitle.isEmpty()) {
            headerLabel.setText(isAutoAdvancing() ? "▶ 自动翻段中" : "");
            return;
        }
        String auto = isAutoAdvancing() ? " · 自动" : "";
        headerLabel.setText(chapterTitle + auto);
    }

    private void render() {
        String text = chunks.isEmpty() ? "" : chunks.get(chunkIndex);
        StyledDocument doc = textPane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 空文档
        }
        try {
            doc.insertString(doc.getLength(), text, new SimpleAttributeSet());
            if (chunks.size() > 0) {
                SimpleAttributeSet progress = new SimpleAttributeSet();
                StyleConstants.setForeground(progress, progressColor());
                doc.insertString(doc.getLength(), "  (" + (chunkIndex + 1) + "/" + chunks.size() + ")",
                        progress);
            }
        } catch (BadLocationException ignored) {
            // 理论上到不了
        }
        updateHeader();
        layoutForWidth();
    }

    /**
     * 单行布局：正文不折行，一次只显示一行（一句），宽度跟着文本走，上限封顶。
     *
     * <p>JTextPane 默认会按容器宽度折行（ParagraphView 响应宽度），所以量单行尺寸时
     * 要给一个远超文本的宽度，让它不折，拿到「一行」的真实宽高；再把弹窗宽度
     * 定为「文本单行宽 + 内边距」，上限 MAX_WIDTH，超出才折。
     */
    private void layoutForWidth() {
        textPane.setSize(MAX_WIDTH, Integer.MAX_VALUE);
        int lineWidth = textPane.getPreferredSize().width;
        int lineHeight = textPane.getPreferredSize().height;
        int headerHeight = headerLabel.getPreferredSize().height;
        int width = Math.min(MAX_WIDTH, lineWidth + 24);
        Dimension size = new Dimension(width, Math.max(36, lineHeight + headerHeight + 12));
        setPreferredSize(size);
        revalidate();
        repaint();
        if (resizeListener != null) {
            resizeListener.accept(size);
        }
    }

    // ---------------- 自动翻段 ----------------

    /**
     * 定时自动翻段（chapter_reader 那种「隔几秒换一句」的挂机模式）。
     * seconds 为正整数；传 0 或负数视为停止。
     * 翻到章尾会通过 nav 越章，所以开着计时器能把整本书连续挂下去。
     */
    public void setAutoAdvance(int seconds) {
        stopAuto();
        if (seconds <= 0) {
            updateHeader();
            return;
        }
        autoTimer = new javax.swing.Timer(seconds * 1000, e -> perform(ChunkAction.NEXT_CHUNK));
        autoTimer.start();
        updateHeader();
    }

    /** 停止自动翻段。关窗、切手动模式都必须调，否则计时器会把弹窗的内容一直往后推 */
    public void stopAuto() {
        if (autoTimer != null) {
            autoTimer.stop();
            autoTimer = null;
            updateHeader();
        }
    }

    public boolean isAutoAdvancing() {
        return autoTimer != null;
    }

    // ---------------- 字号 ----------------

    public void setFontSize(int size) {
        fontSize = Math.max(9, Math.min(48, size));
        applyFont();
        render();
    }

    public int fontSize() {
        return fontSize;
    }

    private void applyFont() {
        textPane.setFont(new Font(CodeDocument.editorFontFamily(), Font.PLAIN, fontSize));
    }

    // ---------------- 配色 ----------------

    /** 跟随平台 ToolTip 配色：真 IDE 里深浅主题各有一套，这里读同一份，不自己发明颜色 */
    public void applyThemeColors() {
        Color bg = colorOrDefault("ToolTip.background", new Color(0x3C, 0x3F, 0x41));
        Color fg = colorOrDefault("ToolTip.foreground", new Color(0xBC, 0xBC, 0xBC));
        setBackground(bg);
        textPane.setForeground(fg);
        // 标题栏颜色比正文淡一档，像 IDE 工具提示里的次要信息
        headerLabel.setForeground(blend(fg, bg, 0.55f));
        repaint();
    }

    private static Color blend(Color a, Color b, float ratio) {
        return new Color(
                Math.round(a.getRed() * ratio + b.getRed() * (1 - ratio)),
                Math.round(a.getGreen() * ratio + b.getGreen() * (1 - ratio)),
                Math.round(a.getBlue() * ratio + b.getBlue() * (1 - ratio)));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // 构造期间父类也可能走到这里，字段还没初始化时跳过
        if (textPane == null) {
            return;
        }
        applyThemeColors();
    }

    private static Color colorOrDefault(String key, Color fallback) {
        Color c = uiManagerColor(key);
        return c == null ? fallback : c;
    }

    private static Color uiManagerColor(String key) {
        try {
            return javax.swing.UIManager.getColor(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 进度数字比正文淡一档：往底色方向混 45% */
    private Color progressColor() {
        Color bg = getBackground();
        Color fg = textPane.getForeground();
        return new Color(
                Math.round(fg.getRed() * 0.55f + bg.getRed() * 0.45f),
                Math.round(fg.getGreen() * 0.55f + bg.getGreen() * 0.45f),
                Math.round(fg.getBlue() * 0.55f + bg.getBlue() * 0.45f));
    }

    // ---------------- 键位 ----------------

    public static ChunkAction resolveKey(KeyStroke stroke) {
        if (stroke == null) {
            return ChunkAction.NONE;
        }
        for (Object[] row : KEY_TABLE) {
            if (row[0].equals(stroke)) {
                return (ChunkAction) row[1];
            }
        }
        return ChunkAction.NONE;
    }

    public static List<KeyStroke> boundKeys() {
        List<KeyStroke> out = new ArrayList<>();
        for (Object[] row : KEY_TABLE) {
            out.add((KeyStroke) row[0]);
        }
        return out;
    }

    private void installKeyBindings() {
        InputMap keys = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = getActionMap();
        int i = 0;
        for (Object[] row : KEY_TABLE) {
            KeyStroke stroke = (KeyStroke) row[0];
            ChunkAction action = (ChunkAction) row[1];
            String name = "ideanovel.mini." + action.name() + "." + (i++);
            keys.put(stroke, name);
            actions.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    perform(action);
                }
            });
        }
    }

    /** 键盘和滚轮共用这一条路径 */
    public void perform(ChunkAction action) {
        switch (action) {
            case CLOSE:
                nav.close();
                break;
            case NEXT_CHUNK:
                nextChunk();
                break;
            case PREV_CHUNK:
                prevChunk();
                break;
            case NEXT_CHAPTER:
                nav.nextChapter();
                break;
            case PREV_CHAPTER:
                nav.prevChapter();
                break;
            case TOGGLE_AUTO:
                nav.toggleAutoAdvance();
                break;
            case CHANGE_AUTO:
                nav.changeAutoSeconds();
                break;
            default:
                break;
        }
    }

    // ---------------- 滚轮 ----------------

    /**
     * 提示框没有滚动条，滚轮就是翻段。
     * 不注册的话 Swing 会把事件交给弹窗后面的编辑器滚代码——
     * 那才是真正灾难性的「滚一下看的是别的文字」。
     */
    private void handleWheel(MouseWheelEvent e) {
        perform(e.getWheelRotation() < 0 ? ChunkAction.PREV_CHUNK : ChunkAction.NEXT_CHUNK);
        e.consume();
    }

    // ---------------- 布局辅助 ----------------

    /** 摆好之后的实际尺寸，宿主看情况用；探针用来确认没有夸张的高宽比 */
    public Dimension laidOutSize() {
        return getPreferredSize();
    }

    /** 供宿主在主题切换后重取色 */
    public void refreshTheme() {
        SwingUtilities.invokeLater(this::applyThemeColors);
    }
}
