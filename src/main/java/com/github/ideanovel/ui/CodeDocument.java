package com.github.ideanovel.ui;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;

import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 「一个 Java 文件」的内容与渲染，源码伪装的唯一实现。
 *
 * <p>改造原因：现在有两种地方要显示这份「源码」——侧边栏的源码伪装
 * （{@link SourceDisguisePanel}）和代码弹出框（{@link CodePopupContent}）。
 * 拼装、转义、配色如果各写一份，迟早出现「同一章在两种阅读方式里长得不一样」，
 * 而且配色要跟 IDE 主题对齐，重复一份就多一份会腐烂的代码。
 *
 * <p>这个类刻意不碰 Swing 容器之外的平台对象之外的任何东西（只用 EditorColorsManager 取色，
 * 取不到就走兜底），所以可以脱离 IDE 直接跑，产出能落盘比对。
 */
public final class CodeDocument {

    /**
     * 兜底配色：拿不到 IDE 方案时用。
     *
     * <p>以前只有 Darcula 一套。问题是这套色是给暗底准备的——浅色主题下
     * 如果方案恰好取不到（非 IDEA 系 IDE、或极端情况下平台服务不可用），
     * 正文、注释、提示行就全糊在浅底上，几乎看不见。
     * 所以按当前背景的明暗选一套：暗底用 Darcula，浅底用 IDEA 浅色主题的经典色。
     */
    static final Color DARK_KEYWORD = new Color(0xCC, 0x78, 0x32);
    static final Color DARK_COMMENT = new Color(0x62, 0x97, 0x55);
    static final Color DARK_STRING = new Color(0x6A, 0x87, 0x59);
    static final Color DARK_METADATA = new Color(0xBB, 0xB5, 0x29);
    static final Color DARK_PLAIN = new Color(0xA9, 0xB7, 0xC6);
    static final Color DARK_BACKGROUND = new Color(0x2B, 0x2B, 0x2B);

    static final Color LIGHT_KEYWORD = new Color(0x00, 0x33, 0xB3);
    static final Color LIGHT_COMMENT = new Color(0x8C, 0x8C, 0x8C);
    static final Color LIGHT_STRING = new Color(0x06, 0x7D, 0x17);
    static final Color LIGHT_METADATA = new Color(0x9E, 0x88, 0x0D);
    static final Color LIGHT_PLAIN = new Color(0x1E, 0x1E, 0x1E);
    static final Color LIGHT_BACKGROUND = new Color(0xFF, 0xFF, 0xFF);

    static final String KIND_KEYWORD = "keyword";
    static final String KIND_COMMENT = "comment";
    static final String KIND_STRING = "string";
    static final String KIND_METADATA = "metadata";
    static final String KIND_PLAIN = "plain";

    private CodeDocument() {
    }

    /** 一段文本和它该用什么颜色，kind 见 KIND_* 常量 */
    public static final class Line {
        final String text;
        final String kind;

        Line(String text, String kind) {
            this.text = text;
            this.kind = kind;
        }

        public String text() {
            return text;
        }

        public String kind() {
            return kind;
        }
    }

    /** 章节号转类名：第 4 章 → Chapter0004 */
    public static String classNameOf(int index) {
        return "Chapter" + String.format("%04d", Math.max(0, index + 1));
    }

    /**
     * 拼出整个 Java 文件的内容。
     *
     * 注意这里返回的是「带颜色标记的片段」而不是逐行的行：
     * 为了让字符串字面量能单独上色（比如 TITLE 的值），一行源码会被拆成多个片段，
     * 所以片段边界不等于行边界——渲染时不能按片段算行号，得按换行符算。
     */
    public static List<Line> buildLines(String title, String body, int index) {
        List<Line> out = new ArrayList<>();
        String className = classNameOf(index);

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

    public static String[] splitLines(String body) {
        if (body == null || body.isEmpty()) {
            return new String[]{"（本章暂无内容）"};
        }
        return body.split("\r?\n");
    }

    /** 正文里出现注释结束符（星号+斜杠）会提前关掉注释块，必须转义，否则整段渲染出来是乱的 */
    public static String escapeComment(String line) {
        return line.replace("*/", "*\\/").replace("\t", "    ");
    }

    /** 标题塞进 Java 字符串常量，引号和反斜杠得转义，不然源码看着不合法 */
    public static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ---------------- 渲染 ----------------

    public static Color colorFor(String kind) {
        switch (kind) {
            case KIND_KEYWORD:
                return colorOf(DefaultLanguageHighlighterColors.KEYWORD,
                        fallback(DARK_KEYWORD, LIGHT_KEYWORD));
            case KIND_COMMENT:
                return commentColor();
            case KIND_STRING:
                return colorOf(DefaultLanguageHighlighterColors.STRING,
                        fallback(DARK_STRING, LIGHT_STRING));
            case KIND_METADATA:
                return colorOf(DefaultLanguageHighlighterColors.METADATA,
                        fallback(DARK_METADATA, LIGHT_METADATA));
            default:
                return plainColor();
        }
    }

    public static SimpleAttributeSet styleFor(String kind) {
        return attr(colorFor(kind));
    }

    /**
     * 写进文档，不带行号。侧边栏伪装用这个——那条工具栏本来就窄，行号纯属占地方。
     */
    public static void writePlain(StyledDocument doc, List<Line> lines) {
        for (Line line : lines) {
            insert(doc, line.text, styleFor(line.kind));
        }
    }

    /**
     * 写进文档，左侧带行号 gutter。弹出框用这个，像真编辑器。
     *
     * <p>行号必须按<b>换行符</b>数，不能按片段数：一个源码行可能由多个片段拼成
     * （见 {@link #buildLines}），按片段加行号会多出一堆假行号。
     */
    public static void writeNumbered(StyledDocument doc, List<Line> lines) {
        int total = countLines(lines);
        int width = String.valueOf(Math.max(1, total)).length();
        SimpleAttributeSet gutter = attr(gutterColor());
        int lineNo = 1;
        boolean atLineStart = true;

        for (Line line : lines) {
            String text = line.text;
            int from = 0;
            while (from <= text.length()) {
                int nl = text.indexOf('\n', from);
                String chunk = nl < 0 ? text.substring(from) : text.substring(from, nl + 1);
                if (atLineStart && !chunk.isEmpty()) {
                    insert(doc, numberLabel(lineNo++, width), gutter);
                    atLineStart = false;
                }
                if (!chunk.isEmpty()) {
                    insert(doc, chunk, styleFor(line.kind));
                }
                if (nl < 0) {
                    break;
                }
                atLineStart = true;
                from = nl + 1;
            }
        }
    }

    /** 行号 + 间距，右对齐到统一宽度，数字列才不会参差不齐 */
    static String numberLabel(int lineNo, int width) {
        StringBuilder sb = new StringBuilder();
        String s = String.valueOf(lineNo);
        for (int i = s.length(); i < width; i++) {
            sb.append(' ');
        }
        sb.append(s).append("  ");
        return sb.toString();
    }

    /** 整份文档一共多少行（按换行符数），行号列的宽度按它算 */
    public static int countLines(List<Line> lines) {
        int n = 0;
        for (Line line : lines) {
            for (int i = 0; i < line.text.length(); i++) {
                if (line.text.charAt(i) == '\n') {
                    n++;
                }
            }
        }
        return Math.max(1, n);
    }

    // ---------------- 取色 ----------------

    /** 编辑器默认背景，弹出框拿它当底色，看起来就是个源码视图 */
    public static Color backgroundColor() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                Color c = scheme.getDefaultBackground();
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception ignored) {
            // 离线时拿不到，走兜底
        }
        Color ui = javax.swing.UIManager.getColor("Panel.background");
        return ui == null ? fallbackBackground() : ui;
    }

    /** 兜底背景：按当前明暗挑，浅色下给白底而不是 Darcula 的深灰 */
    static Color fallbackBackground() {
        return fallback(DARK_BACKGROUND, LIGHT_BACKGROUND);
    }

    /** 当前 UI 是不是暗底。拿不到任何颜色时按暗底算（Darcula 最常见） */
    static boolean darkUi() {
        Color ui = javax.swing.UIManager.getColor("Panel.background");
        return ui == null || isDark(ui);
    }

    /** 兜底色按明暗二选一 */
    static Color fallback(Color dark, Color light) {
        return darkUi() ? dark : light;
    }

    /**
     * 编辑器字体名。字体比配色更容易穿帮，所以两份「源码视图」都得用它。
     * 取不到（离线、或非 IDEA 系 IDE）时退回等宽字体。
     */
    public static String editorFontFamily() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                String fn = scheme.getEditorFontName();
                if (fn != null && !fn.isEmpty()) {
                    return fn;
                }
            }
        } catch (Exception ignored) {
            // 走兜底
        }
        return java.awt.Font.MONOSPACED;
    }

    /** 编辑器字号，取不到给 13 */
    public static int editorFontSize() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                int fs = scheme.getEditorFontSize();
                if (fs > 0) {
                    return fs;
                }
            }
        } catch (Exception ignored) {
            // 走兜底
        }
        return 13;
    }

    /** 正文（非关键字部分）用编辑器默认前景色，和真源码视图一致 */
    public static Color plainColor() {
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
        return fallback(DARK_PLAIN, LIGHT_PLAIN);
    }

    public static Color commentColor() {
        // 文档注释优先，没有就用块注释；两者都拿不到才兜底
        Color c = colorOf(DefaultLanguageHighlighterColors.DOC_COMMENT, null);
        return c != null ? c
                : colorOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT,
                        fallback(DARK_COMMENT, LIGHT_COMMENT));
    }

    /** 行号颜色：取 IDE 的 gutter 行号色，取不到就按背景明暗挑一个不抢眼的灰 */
    public static Color gutterColor() {
        // 注意：行号色在平台里是 ColorKey，不是 TextAttributesKey，
        // 走下面那个取 attributes 的辅助方法会直接编译不过。
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                Color c = scheme.getColor(EditorColors.LINE_NUMBERS_COLOR);
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception ignored) {
            // 离线时拿不到，走兜底
        }
        return darkUi() ? new Color(0x60, 0x64, 0x68) : new Color(0x99, 0x99, 0x99);
    }

    /** 行号背景：不用硬编码色，按前景/背景混一点，深浅主题都成立 */
    public static Color gutterBackground() {
        return blend(backgroundColor(), gutterColor(), 0.12f);
    }

    /** 提示行（弹出框底部那句快捷键说明）的颜色，比行号再淡一点，但保证能看清 */
    public static Color hintColor() {
        Color hint = blend(backgroundColor(), plainColor(), 0.55f);
        // 混出来要是和背景太接近（比如兜底色本身和背景一个调），就往前景再压一压
        if (Math.abs(luma(hint) - luma(backgroundColor())) < 30) {
            hint = blend(backgroundColor(), plainColor(), 0.8f);
        }
        return hint;
    }

    static double luma(Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
    }

    static boolean isDark(Color c) {
        double luma = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return luma < 128;
    }

    /** 把 fg 按 ratio 混进 bg，用来在任意主题下得到一个「介于两者之间」的色 */
    static Color blend(Color bg, Color fg, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        return new Color(
                Math.round(bg.getRed() * (1 - r) + fg.getRed() * r),
                Math.round(bg.getGreen() * (1 - r) + fg.getGreen() * r),
                Math.round(bg.getBlue() * (1 - r) + fg.getBlue() * r));
    }

    static Color colorOf(TextAttributesKey key, Color fallback) {
        if (key == null) {
            return fallback;
        }
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

    static SimpleAttributeSet attr(Color c) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, c);
        return set;
    }

    static void insert(StyledDocument doc, String text, SimpleAttributeSet style) {
        if (text.isEmpty()) {
            return;
        }
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {
            // 插入失败不影响界面
        }
    }
}
