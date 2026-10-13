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
    }

    public enum ChunkAction {
        NONE, CLOSE, NEXT_CHUNK, PREV_CHUNK, NEXT_CHAPTER, PREV_CHAPTER
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
    };

    /** 提示框的目标宽度（像素）。太宽就从小提示变成小报纸了 */
    private static final int PREFERRED_WIDTH = 380;

    private final JTextPane textPane = new JTextPane();

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
    };

    private int fontSize = 13;
    private List<String> chunks = new ArrayList<>();
    private int chunkIndex;
    private int chapterIndex;

    public MiniTooltipContent() {
        setLayout(new java.awt.BorderLayout());
        textPane.setEditable(false);
        textPane.setFocusable(false);
        textPane.setOpaque(false);
        textPane.addMouseWheelListener(this::handleWheel);
        add(textPane, java.awt.BorderLayout.CENTER);

        // 自己要能拿焦点，按键才落到 WHEN_IN_FOCUSED_WINDOW 绑定上
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setBorder(BorderFactory.createEmptyBorder(7, 11, 9, 11));
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
        chunkIndex = jumpToEnd ? Math.max(0, chunks.size() - 1) : 0;
        render();
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
        layoutForWidth();
    }

    /**
     * JTextPane 的首选高度是按当前宽度折行算的，得先给定宽度再量高度，
     * 否则长段会把提示框撑成一条竖线或一条横带。
     */
    private void layoutForWidth() {
        textPane.setSize(PREFERRED_WIDTH - 24, Short.MAX_VALUE);
        int height = textPane.getPreferredSize().height;
        Dimension size = new Dimension(PREFERRED_WIDTH, height + 18);
        setPreferredSize(size);
        revalidate();
        repaint();
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
        repaint();
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
