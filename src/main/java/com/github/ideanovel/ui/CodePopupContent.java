package com.github.ideanovel.ui;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.StyledDocument;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码弹出框的内容：一个漂浮在编辑器上方、长得像源码视图的阅读窗口。
 *
 * <p>存在的理由：侧边栏阅读要占一块固定面板，而老板键是「整块换掉」，
 * 都很重。这个弹出框是轻量的——按一下快捷键在光标附近浮出来，
 * Esc 就散，视觉上就是 IDE 自带的「快速文档」弹窗，鼠标一天到晚乱点也不会有人怀疑。
 *
 * <p>内容复用 {@link CodeDocument}，所以弹出的「文件」和侧边栏伪装看到的是同一份东西。
 *
 * <p>这个类刻意只用标准 Swing，不碰任何 IntelliJ 平台类：
 * 一来弹出框的宿主（JBPopup）在 IDE 之外根本创建不了，
 * 二来把内容和宿主拆开之后，这个面板可以脱离 IDE 直接渲染成 PNG 比对观感
 * （见 tools/probe/CodePopupProbe.java）。
 */
public class CodePopupContent extends JPanel {

    /** 弹出框要回调外部才能做的事，由宿主（CodePopupReader）接到阅读服务上 */
    public interface Nav {
        void close();

        void nextChapter();

        void prevChapter();

        void changeFontSize(int delta);
    }

    /** 键盘/滚轮能触发的动作。抽成枚举是为了让「键位表」和「实际行为」一一对应。 */
    public enum NavAction {
        NONE, CLOSE, NEXT_CHAPTER, PREV_CHAPTER, FONT_BIGGER, FONT_SMALLER,
        SCROLL_UP, SCROLL_DOWN, SCROLL_PAGE_UP, SCROLL_PAGE_DOWN
    }

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    /**
     * 键位表，同时也是 {@link #resolveKey} 的唯一数据源。
     *
     * 故意做成「一张表驱动绑定与查询」：如果手写一套 KeyStroke 绑定、
     * 另外手写一遍 resolveKey 的 if-else，两边迟早会漂移
     * （改了绑定忘了改判断），而那种 bug 只有真的按下去才会发现。
     */
    private static final Object[][] KEY_TABLE = {
            {KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), NavAction.CLOSE},
            {KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), NavAction.SCROLL_UP},
            {KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), NavAction.SCROLL_DOWN},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), NavAction.SCROLL_PAGE_UP},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), NavAction.SCROLL_PAGE_DOWN},
            // 翻章统一挂 Ctrl：不让方向键本身既滚动又翻章，按错了会很突然
            {KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, CTRL), NavAction.NEXT_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_UP, CTRL), NavAction.PREV_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, CTRL), NavAction.NEXT_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, CTRL), NavAction.PREV_CHAPTER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, CTRL), NavAction.FONT_BIGGER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_ADD, CTRL), NavAction.FONT_BIGGER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, CTRL | SHIFT), NavAction.FONT_BIGGER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, CTRL), NavAction.FONT_SMALLER},
            {KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, CTRL), NavAction.FONT_SMALLER},
    };

    private static final int DEFAULT_FONT_SIZE = 13;

    private final JTextPane codePane = new JTextPane();
    private final JScrollPane scrollPane = new JScrollPane(codePane);
    private final JLabel fileLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel(" ");

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
        public void changeFontSize(int delta) {
        }
    };

    private int fontSize = DEFAULT_FONT_SIZE;
    private int chapterIndex;
    private int chapterTotal;
    private String chapterTitle = "";
    private String fileName = "Chapter0001.java";

    public CodePopupContent() {
        setLayout(new BorderLayout());
        setBackground(CodeDocument.backgroundColor());

        codePane.setEditable(false);
        // 不抢焦点：这样按键统一走本面板的 WHEN_IN_FOCUSED_WINDOW 绑定，
        // 不会出现「方向键被文本框的插入符吃掉、翻章键时灵时不灵」。
        // 顺带效果是弹窗里不会闪一个光标，更像 IDE 自带的文档弹窗而不是编辑器。
        codePane.setFocusable(false);
        codePane.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 10));
        codePane.setBackground(CodeDocument.backgroundColor());
        codePane.addMouseWheelListener(this::handleWheel);

        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setBackground(CodeDocument.backgroundColor());
        // JTextPane 的首选尺寸是按「最长那一行」算的，网文一段几百字能把弹出框撑成
        // 一条横穿屏幕的长条。所以固定成接近 IDE 快速文档弹窗的尺寸，
        // 之后用户可以自己拖（宿主开了可缩放，尺寸会记住）。
        scrollPane.setPreferredSize(new Dimension(620, 420));

        // 文件名画在内容里而不是用 JBPopup 的标题栏：平台的标题只能在创建时给一次，
        // 而这里要随章节变（Chapter0004.java → Chapter0005.java）。
        fileLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        fileLabel.setOpaque(true);

        hintLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 4, 8));
        hintLabel.setOpaque(true);

        add(fileLabel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(hintLabel, BorderLayout.SOUTH);

        // 面板自己要能拿焦点，按键才会落到下面的 WHEN_IN_FOCUSED_WINDOW 绑定上
        setFocusable(true);
        installKeyBindings();
        applyFont();
        applyThemeColors();
    }

    // ---------------- 内容 ----------------

    /** 塞入一章。index 从 0 起，total 是总章数（只用于底部提示） */
    public void setChapter(String title, String body, int index, int total) {
        chapterIndex = Math.max(0, index);
        chapterTotal = Math.max(0, total);
        chapterTitle = title == null ? "" : title;
        fileName = CodeDocument.classNameOf(chapterIndex) + ".java";

        StyledDocument doc = codePane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 空文档
        }
        // 弹出框带行号：这里模仿的是一个真编辑器视图，行号是最直观的那个信号
        CodeDocument.writeNumbered(doc, CodeDocument.buildLines(chapterTitle, body, chapterIndex));
        codePane.setCaretPosition(0);
        updateFileLabel();
        scrollToTop();
        updateHint();
    }

    /** 没书可读时的占位内容，让用户知道快捷键叫什么 */
    public void setPlaceholder(String message) {
        chapterIndex = 0;
        chapterTotal = 0;
        chapterTitle = "无题";
        fileName = CodeDocument.classNameOf(0) + ".java";
        StyledDocument doc = codePane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 空文档
        }
        CodeDocument.writeNumbered(doc, CodeDocument.buildLines(chapterTitle,
                message == null ? "" : message, 0));
        codePane.setCaretPosition(0);
        updateFileLabel();
        scrollToTop();
        updateHint();
    }

    private void updateFileLabel() {
        fileLabel.setText(fileName);
    }

    /** 弹出框标题栏显示的文件名，长得像个真源码文件 */
    public String fileName() {
        return fileName;
    }

    /**
     * 当前渲染出来的源码文本（不含颜色），探针和调试用。
     *
     * 直接问文档要，不用 {@code JTextComponent.getText()}：后者在 Windows 上会把行分隔符
     * 换成本平台习惯的 CRLF（文档里其实存的是 LF），拿去做逐行断言会平白多出一堆 \r，
     * 看起来像渲染出了问题。
     */
    public String codeText() {
        try {
            StyledDocument doc = codePane.getStyledDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    public String hintText() {
        return hintLabel.getText();
    }

    public JTextPane codePane() {
        return codePane;
    }

    public JScrollPane scrollPane() {
        return scrollPane;
    }

    public void setNav(Nav nav) {
        if (nav != null) {
            this.nav = nav;
        }
    }

    private void updateHint() {
        StringBuilder sb = new StringBuilder("// ");
        if (chapterTotal > 0) {
            sb.append("第 ").append(chapterIndex + 1).append(" / ").append(chapterTotal).append(" 章");
            sb.append(" · ").append(chapterTitle);
            sb.append(" · Ctrl+↑↓ 翻章");
        } else {
            sb.append("还没有打开小说 · 先在「摸鱼小说」窗口里打开一本");
        }
        sb.append(" · Ctrl+滚轮 字号 · Esc 关闭");
        hintLabel.setText(sb.toString());
    }

    // ---------------- 字号 ----------------

    public void setCodeFontSize(int size) {
        fontSize = Math.max(9, Math.min(48, size));
        applyFont();
        revalidate();
        repaint();
    }

    public int codeFontSize() {
        return fontSize;
    }

    private void applyFont() {
        Font code = new Font(CodeDocument.editorFontFamily(), Font.PLAIN, fontSize);
        codePane.setFont(code);
        fileLabel.setFont(code);
        hintLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, Math.max(10, fontSize - 2)));
    }

    // ---------------- 主题 ----------------

    /** 深浅主题切换时重取颜色。颜色不能只在构造时算一次，否则切主题会出现「底是旧的、字是新的」 */
    public void applyThemeColors() {
        Color bg = CodeDocument.backgroundColor();
        setBackground(bg);
        codePane.setBackground(bg);
        codePane.setForeground(CodeDocument.plainColor());
        codePane.setCaretColor(CodeDocument.plainColor());
        scrollPane.getViewport().setBackground(bg);
        scrollPane.setBackground(bg);
        fileLabel.setBackground(CodeDocument.gutterBackground());
        fileLabel.setForeground(CodeDocument.hintColor());
        hintLabel.setBackground(CodeDocument.gutterBackground());
        hintLabel.setForeground(CodeDocument.hintColor());
        // 行号/注释的颜色是写进文档属性的，切主题后得重渲染才会变
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // 构造期间父类也可能走到这里，此时字段还没初始化
        if (codePane == null || hintLabel == null || fileLabel == null) {
            return;
        }
        applyThemeColors();
    }

    // ---------------- 键位 ----------------

    /** 查询某个按键对应什么动作；与 {@link #KEY_TABLE} 同源，不会和实际绑定漂移 */
    public static NavAction resolveKey(KeyStroke stroke) {
        if (stroke == null) {
            return NavAction.NONE;
        }
        for (Object[] row : KEY_TABLE) {
            if (row[0].equals(stroke)) {
                return (NavAction) row[1];
            }
        }
        return NavAction.NONE;
    }

    /** 键位表里所有绑定的按键，探针用它来确认绑定真的装上了 */
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
            NavAction action = (NavAction) row[1];
            String name = "ideanovel.code." + action.name() + "." + (i++);
            keys.put(stroke, name);
            actions.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    perform(action);
                }
            });
        }
    }

    /** 执行一个动作。滚轮和键盘共用这一条路径，行为不会两样 */
    public void perform(NavAction action) {
        switch (action) {
            case CLOSE:
                nav.close();
                break;
            case NEXT_CHAPTER:
                nav.nextChapter();
                break;
            case PREV_CHAPTER:
                nav.prevChapter();
                break;
            case FONT_BIGGER:
                nav.changeFontSize(1);
                break;
            case FONT_SMALLER:
                nav.changeFontSize(-1);
                break;
            case SCROLL_UP:
                scroll(false, -1);
                break;
            case SCROLL_DOWN:
                scroll(false, 1);
                break;
            case SCROLL_PAGE_UP:
                scroll(true, -1);
                break;
            case SCROLL_PAGE_DOWN:
                scroll(true, 1);
                break;
            default:
                break;
        }
    }

    // ---------------- 滚动 ----------------

    /**
     * 弹出框里的滚轮。
     *
     * <p><b>Swing 的坑（和正文区同一个）</b>：给文本组件注册了 MouseWheelListener 之后，
     * 滚轮事件就被重定向到这里并<b>就地截止</b>，外层滚动容器收不到，
     * 默认滚动逻辑整个失效——表现就是「滚轮完全没反应」。
     * 所以不按 Ctrl 时必须在这里把滚动自己补回来。
     */
    private void handleWheel(MouseWheelEvent e) {
        if (e.isControlDown()) {
            // Ctrl+滚轮调字号，和正文区保持一致
            nav.changeFontSize(e.getWheelRotation() < 0 ? 1 : -1);
            e.consume();
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) {
            return;
        }
        Rectangle visible = scrollPane.getViewport().getViewRect();
        int direction = e.getWheelRotation() < 0 ? -1 : 1;
        int delta;
        if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            // 单位必须问 JTextPane 要：滚动条自己的 getUnitIncrement() 返回 1（像素），滚起来等于不动
            delta = codePane.getScrollableUnitIncrement(visible, SwingConstants.VERTICAL, direction)
                    * e.getUnitsToScroll();
        } else {
            delta = direction
                    * codePane.getScrollableBlockIncrement(visible, SwingConstants.VERTICAL, direction);
        }
        setBarValue(bar, bar.getValue() + delta);
        e.consume();
    }

    /** 键盘滚动：按行或按页 */
    private void scroll(boolean page, int direction) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null) {
            return;
        }
        Rectangle visible = scrollPane.getViewport().getViewRect();
        int delta = page
                ? codePane.getScrollableBlockIncrement(visible, SwingConstants.VERTICAL, direction)
                : codePane.getScrollableUnitIncrement(visible, SwingConstants.VERTICAL, direction);
        setBarValue(bar, bar.getValue() + direction * delta);
    }

    private static void setBarValue(JScrollBar bar, int value) {
        int max = bar.getMaximum() - bar.getModel().getExtent();
        bar.setValue(Math.max(0, Math.min(value, Math.max(0, max))));
    }

    private void scrollToTop() {
        // 新内容刚灌进去时布局还没走完，滚动条的 max 可能还是旧值，等一拍再拉一次
        scrollPane.getViewport().setViewPosition(new Point(0, 0));
        SwingUtilities.invokeLater(() -> {
            scrollPane.getViewport().setViewPosition(new Point(0, 0));
            scrollPane.getVerticalScrollBar().setValue(0);
        });
    }
}
