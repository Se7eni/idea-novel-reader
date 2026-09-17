package com.github.ideanovel.ui;

import com.github.ideanovel.service.NovelReaderService;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * 源码伪装面板：把小说正文渲染成一个 Java 文件的样子，正文藏在 Javadoc 注释块里。
 *
 * 和另外三种伪装的区别：那三种是「假装在干别的」，看不了小说；
 * 这种是「看起来在读代码，实际上还在读小说」——老板扫一眼是源码视图，
 * 而且侧边栏里出现源码视图很自然（不像构建日志那样反常）。
 *
 * 配色和字体都取自 IDE 当前编辑器方案，和真编辑器保持一致。
 */
public class SourceDisguisePanel extends javax.swing.JPanel {

    /** 兜底配色：拿不到 IDE 方案时用 Darcula 的经典色 */
    private static final Color FALLBACK_KEYWORD = new Color(0xCC, 0x78, 0x32);
    private static final Color FALLBACK_COMMENT = new Color(0x62, 0x97, 0x55);
    private static final Color FALLBACK_STRING = new Color(0x6A, 0x87, 0x59);
    private static final Color FALLBACK_METADATA = new Color(0xBB, 0xB5, 0x29);
    private static final Color FALLBACK_PLAIN = new Color(0xA9, 0xB7, 0xC6);

    private final JTextPane pane = new JTextPane();

    public SourceDisguisePanel() {
        setLayout(new BorderLayout());
        pane.setEditable(false);
        applyEditorFont();
        add(new JBScrollPane(pane), BorderLayout.CENTER);
    }

    /** 字体跟 IDE 编辑器一致。字体比配色更容易穿帮。 */
    private void applyEditorFont() {
        String name = Font.MONOSPACED;
        int size = 13;
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                String fn = scheme.getEditorFontName();
                if (fn != null && !fn.isEmpty()) {
                    name = fn;
                }
                int fs = scheme.getEditorFontSize();
                if (fs > 0) {
                    size = fs;
                }
            }
        } catch (Exception ignored) {
            // 取不到就用等宽默认值
        }
        pane.setFont(new Font(name, Font.PLAIN, size));
    }

    /** 重新取当前章节渲染。切进伪装态、切章时都要调一次。 */
    public void rebuild() {
        NovelReaderService service = NovelReaderService.getInstance();
        render(safeTitle(service), safeText(service), service.getChapterIndex());
    }

    private static String safeTitle(NovelReaderService s) {
        try {
            String t = s.currentChapterTitle();
            return t == null || t.isEmpty() ? "无题" : t;
        } catch (Exception e) {
            return "无题";
        }
    }

    private static String safeText(NovelReaderService s) {
        try {
            String t = s.currentText();
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    /** 一行的文本和它该用什么颜色，kind 见 KIND_* 常量 */
    static final class Line {
        final String text;
        final String kind;

        Line(String text, String kind) {
            this.text = text;
            this.kind = kind;
        }
    }

    static final String KIND_KEYWORD = "keyword";
    static final String KIND_COMMENT = "comment";
    static final String KIND_STRING = "string";
    static final String KIND_METADATA = "metadata";
    static final String KIND_PLAIN = "plain";

    /**
     * 拼出整个 Java 文件的内容。
     *
     * 抽成不碰 Swing 的纯函数，是为了能脱离 IDE 直接跑测试看产出——
     * 这个面板本身依赖 JBScrollPane，脱离 IDE 根本实例化不了。
     */
    static List<Line> buildLines(String title, String body, int index) {
        List<Line> out = new ArrayList<>();
        String className = "Chapter" + String.format("%04d", Math.max(0, index + 1));

        out.add(new Line("package com.example.demo;\n\n", KIND_KEYWORD));
        out.add(new Line("import java.util.List;\n", KIND_KEYWORD));
        out.add(new Line("import java.util.Objects;\n\n", KIND_KEYWORD));

        out.add(new Line("/**\n", KIND_COMMENT));
        out.add(new Line(" * " + escapeComment(title) + "\n", KIND_COMMENT));
        out.add(new Line(" *\n", KIND_COMMENT));
        // 正文进注释块：每行加 " * " 前缀，看起来就是标准 Javadoc
        for (String line : splitLines(body)) {
            out.add(new Line(" * " + escapeComment(line) + "\n", KIND_COMMENT));
        }
        out.add(new Line(" */\n", KIND_COMMENT));

        out.add(new Line("@SuppressWarnings(\"unused\")\n", KIND_METADATA));
        out.add(new Line("public final class " + className + " {\n\n", KIND_PLAIN));
        out.add(new Line("    private static final String TITLE = ", KIND_PLAIN));
        out.add(new Line("\"" + escapeString(title) + "\";\n\n", KIND_STRING));
        out.add(new Line("    private " + className + "() {\n", KIND_PLAIN));
        out.add(new Line("    }\n\n", KIND_PLAIN));
        out.add(new Line("    public static String title() {\n", KIND_PLAIN));
        out.add(new Line("        return Objects.requireNonNull(TITLE", KIND_PLAIN));
        out.add(new Line(", \"title must not be null\");\n", KIND_STRING));
        out.add(new Line("    }\n", KIND_PLAIN));
        out.add(new Line("}\n", KIND_PLAIN));
        return out;
    }

    private void render(String title, String body, int index) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 空文档，忽略
        }

        SimpleAttributeSet keyword = attr(colorOf(DefaultLanguageHighlighterColors.KEYWORD, FALLBACK_KEYWORD));
        SimpleAttributeSet comment = attr(commentColor());
        SimpleAttributeSet string = attr(colorOf(DefaultLanguageHighlighterColors.STRING, FALLBACK_STRING));
        SimpleAttributeSet metadata = attr(colorOf(DefaultLanguageHighlighterColors.METADATA, FALLBACK_METADATA));
        SimpleAttributeSet plain = attr(plainColor());

        for (Line line : buildLines(title, body, index)) {
            switch (line.kind) {
                case KIND_KEYWORD:
                    append(doc, line.text, keyword);
                    break;
                case KIND_COMMENT:
                    append(doc, line.text, comment);
                    break;
                case KIND_STRING:
                    append(doc, line.text, string);
                    break;
                case KIND_METADATA:
                    append(doc, line.text, metadata);
                    break;
                default:
                    append(doc, line.text, plain);
                    break;
            }
        }

        pane.setCaretPosition(0);
    }

    /**
     * 切进伪装时把滚动位置对齐到阅读区的比例。
     * 长章节从头开始会找不到刚才读的位置，按比例对齐至少视觉上是连续的。
     */
    public void scrollToRatio(float ratio) {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollBar();
            if (bar == null) {
                return;
            }
            int max = bar.getMaximum() - bar.getVisibleAmount();
            if (max <= 0) {
                return;
            }
            bar.setValue(Math.max(0, Math.min(max, (int) (max * ratio))));
        });
    }

    private JScrollBar scrollBar() {
        Container p = pane.getParent();
        while (p != null && !(p instanceof JScrollPane)) {
            p = p.getParent();
        }
        return p == null ? null : ((JScrollPane) p).getVerticalScrollBar();
    }

    private static String[] splitLines(String body) {
        if (body.isEmpty()) {
            return new String[]{"（本章暂无内容）"};
        }
        return body.split("\r?\n");
    }

    /** 正文里出现注释结束符（星号+斜杠）会提前关掉注释块，必须转义，否则整段渲染出来是乱的 */
    private static String escapeComment(String line) {
        return line.replace("*/", "*\\/").replace("\t", "    ");
    }

    /** 标题塞进 Java 字符串常量，引号和反斜杠得转义，不然源码看着不合法 */
    private static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static SimpleAttributeSet attr(Color c) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, c);
        return set;
    }

    private static void append(StyledDocument doc, String text, SimpleAttributeSet style) {
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {
            // 插入失败不影响界面
        }
    }

    private static Color plainColor() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                Color fg = scheme.getDefaultForeground();
                if (fg != null) {
                    return fg;
                }
            }
        } catch (Exception ignored) {
            // 走兜底
        }
        return FALLBACK_PLAIN;
    }

    private static Color commentColor() {
        // 文档注释优先，没有就用块注释；两者都拿不到才兜底
        Color c = colorOf(DefaultLanguageHighlighterColors.DOC_COMMENT, null);
        return c != null ? c : colorOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT, FALLBACK_COMMENT);
    }

    private static Color colorOf(TextAttributesKey key, Color fallback) {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                TextAttributes ta = scheme.getAttributes(key);
                if (ta != null) {
                    Color fg = ta.getForegroundColor();
                    if (fg != null) {
                        return fg;
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到就用兜底色
        }
        return fallback;
    }
}
