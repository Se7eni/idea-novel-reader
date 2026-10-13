import com.github.ideanovel.ui.CodeDocument;
import com.github.ideanovel.ui.CodePopupContent;

import javax.imageio.ImageIO;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码弹出框的离线验证：渲染成 PNG 看观感，同时断言那些「只有真按下去才会发现」的行为。
 *
 * <p>弹出框的宿主是 JBPopup，IDE 之外创建不了，所以这里验的是内容面板
 * （CodePopupContent，纯 Swing）。宿主的接线（浮在哪、怎么关）在真 IDE 里才能看到。
 *
 * <p>主观感必须真的看图，光「没报错」等于没验证。
 */
public class CodePopupProbe {

    private static final String TITLE = "第 84 章 好舅舅";
    private static final String BODY = sampleBody();

    private static int failed;

    public static void main(String[] args) throws Exception {
        // ---- 行为断言（先跑，失败也照样出图）----
        checkKeyTable();
        checkChapterRendering();
        checkWheelScrolls();
        checkWheelWithCtrlChangesFont();
        checkNavCallbacks();
        checkLabelsNotClipped();
        checkColorsFollowTheme();

        applyTheme(true);
        render("docs/screenshots/code-popup-dark.png", true);
        applyTheme(false);
        render("docs/screenshots/code-popup-light.png", false);

        System.out.println(failed == 0 ? "ALL CHECKS PASSED" : (failed + " CHECK(S) FAILED"));
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- 行为 ----------------

    /** 键位表里的每个键都得真的绑上了，光有 resolveKey 不算数 */
    private static void checkKeyTable() {
        CodePopupContent content = newContent();
        InputMap keys = content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = content.getActionMap();
        int bound = 0;
        for (KeyStroke stroke : CodePopupContent.boundKeys()) {
            Object binding = keys.get(stroke);
            boolean ok = binding != null && actions.get(binding) != null;
            if (!ok) {
                fail("按键没装上：" + stroke);
            } else {
                bound++;
            }
        }
        want(bound == CodePopupContent.boundKeys().size(),
                "键位表全部装上（%d 个）".formatted(bound));

        // 抽查几个映射，方向键和翻章键不能搞混
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
                        == CodePopupContent.NavAction.CLOSE,
                "Esc → 关闭");
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(
                        KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK))
                        == CodePopupContent.NavAction.NEXT_CHAPTER,
                "Ctrl+↓ → 下一章");
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(
                        KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK))
                        == CodePopupContent.NavAction.PREV_CHAPTER,
                "Ctrl+↑ → 上一章");
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))
                        == CodePopupContent.NavAction.SCROLL_DOWN,
                "↓ → 滚动一行");
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(
                        KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK))
                        == CodePopupContent.NavAction.FONT_SMALLER,
                "Ctrl+- → 缩小字号");
        want(CodePopupContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                        InputEvent.CTRL_DOWN_MASK)) == CodePopupContent.NavAction.NONE,
                "未绑定的键 → NONE");
    }

    /** 行号要按换行符算，不是按片段算——一个源码行可能由多个彩色片段拼成 */
    private static void checkChapterRendering() {
        CodePopupContent content = newContent();
        content.setChapter(TITLE, BODY, 3, 413);
        String text = content.codeText();

        want(content.fileName().equals("Chapter0004.java"),
                "文件名按章号生成（%s）".formatted(content.fileName()));
        want(text.contains(" * " + TITLE), "章标题进了 Javadoc 第一行");
        want(text.contains(" * 他推开门的时候，屋里的灯还亮着。"), "正文进了注释块");
        // 正文里若出现注释结束符（星号+斜杠），会提前关掉注释块，整段渲染就乱了
        want(text.contains("*\\/"), "正文里的注释结束符被转义");
        want(text.contains("public final class Chapter0004 {"), "类名与章号一致");

        int expected = CodeDocument.countLines(CodeDocument.buildLines(TITLE, BODY, 3));
        // codeText() 走的是文档 API。如果哪天有人改成 JTextComponent.getText()，
        // 在 Windows 上会平白多出 CR（文档里存的是 LF），逐行断言就会开始怪叫。
        want(!text.contains("\r"), "渲染出来的文本不含 CR（长度 %d）".formatted(text.length()));

        String[] lines = text.split("\n", -1);
        // 文档以换行结尾，split 会多给一个空元素，减掉才是真实行数
        int actualLines = lines.length - 1;
        want(actualLines == expected,
                "行数与预期一致（实际 %d 行，期望 %d）".formatted(actualLines, expected));

        // 每一行都该以「右对齐行号 + 两个空格」开头，且行号与行序一致。
        // 这条同时覆盖了空行带行号、行号列宽统一（右对齐）两件事。
        int gutter = String.valueOf(expected).length() + 2;
        String bad = null;
        for (int i = 0; i < Math.min(expected, lines.length); i++) {
            String line = lines[i];
            if (line.length() < gutter) {
                bad = "第 %d 行太短「%s」".formatted(i + 1, abbrev(line));
                break;
            }
            String prefix = line.substring(0, gutter);
            if (!prefix.endsWith("  ")) {
                bad = "第 %d 行行号后缺分隔「%s」".formatted(i + 1, abbrev(prefix));
                break;
            }
            int no;
            try {
                no = Integer.parseInt(prefix.trim());
            } catch (NumberFormatException nfe) {
                bad = "第 %d 行行号不是数字「%s」".formatted(i + 1, abbrev(prefix));
                break;
            }
            if (no != i + 1) {
                bad = "第 %d 行行号是 %d".formatted(i + 1, no);
                break;
            }
        }
        want(bad == null, "每行行号连续且右对齐（共 %d 行，行号列宽 %d）%s"
                .formatted(expected, gutter, bad == null ? "" : " — " + bad));

        // 空行也得有行号，像真编辑器那样
        boolean blankNumbered = false;
        for (int i = 0; i < expected; i++) {
            if (lines[i].substring(gutter).isEmpty()) {
                blankNumbered = true;
                break;
            }
        }
        want(blankNumbered, "空行也带行号（像真编辑器）%s".formatted(
                blankNumbered ? "" : " — 前 6 行：" + dump(lines, gutter)));

        want(content.hintText().contains("第 4 / 413 章"), "底部提示显示章节位置");
        want(content.hintText().contains("Esc 关闭"), "底部提示写明怎么关");
    }

    /**
     * 滚轮必须真的滚得动。
     *
     * 这是本插件踩过的老坑：给文本组件注册 MouseWheelListener 会就地吃掉滚轮事件，
     * 外层滚动容器收不到，表现就是「滚轮完全没反应」。这里注入真实滚轮事件来验。
     */
    private static void checkWheelScrolls() {
        CodePopupContent content = laidOut("第 1 章 长章节", longBody(), 0, 20);
        int before = content.scrollPane().getVerticalScrollBar().getValue();
        wheel(content, 3, false);
        int after = content.scrollPane().getVerticalScrollBar().getValue();
        want(before == 0, "滚轮前停在顶部");
        want(after > before, "向下滚轮生效（%d → %d）".formatted(before, after));

        wheel(content, -3, false);
        int back = content.scrollPane().getVerticalScrollBar().getValue();
        want(back < after, "向上滚轮生效（%d → %d）".formatted(after, back));
    }

    private static void checkWheelWithCtrlChangesFont() {
        CodePopupContent content = laidOut("第 1 章 长章节", longBody(), 0, 20);
        final int[] delta = {0};
        content.setNav(new SilentNav() {
            @Override
            public void changeFontSize(int d) {
                delta[0] += d;
            }
        });
        int sizeBefore = content.codeFontSize();
        wheel(content, -1, true);
        want(delta[0] == 1, "Ctrl+滚轮上报放大（delta=%d）".formatted(delta[0]));
        // 宿主会真的改字号，这里手动模拟一次，确认字号确实作用到组件上
        content.setCodeFontSize(sizeBefore + 1);
        want(content.codePane().getFont().getSize() == sizeBefore + 1,
                "字号设置作用到组件（%d → %d）".formatted(sizeBefore,
                        content.codePane().getFont().getSize()));
    }

    /** 键位动作要真的回调到宿主，不能只改自己的状态 */
    private static void checkNavCallbacks() {
        CodePopupContent content = newContent();
        final List<String> calls = new ArrayList<>();
        content.setNav(new SilentNav() {
            @Override
            public void close() {
                calls.add("close");
            }

            @Override
            public void nextChapter() {
                calls.add("next");
            }

            @Override
            public void prevChapter() {
                calls.add("prev");
            }
        });
        content.perform(CodePopupContent.NavAction.NEXT_CHAPTER);
        content.perform(CodePopupContent.NavAction.PREV_CHAPTER);
        content.perform(CodePopupContent.NavAction.CLOSE);
        want(calls.equals(List.of("next", "prev", "close")),
                "键位动作回调到宿主（%s）".formatted(calls));
    }

    /** 文字被裁成省略号是这套 UI 最容易出的问题，这里量一下 */
    private static void checkLabelsNotClipped() {
        CodePopupContent content = laidOut(TITLE, BODY, 3, 413);
        checkFits(content, "底部提示");
        // 顶部文件名换成最长的可能值再量一次
        content.setChapter(TITLE, BODY, 999, 1000);
        checkFits(content, "文件名（4 位数章号）");
    }

    private static void checkFits(CodePopupContent content, String what) {
        for (java.awt.Component c : content.getComponents()) {
            if (!(c instanceof javax.swing.JLabel) || !c.isVisible()) {
                continue;
            }
            javax.swing.JLabel label = (javax.swing.JLabel) c;
            int need = label.getPreferredSize().width;
            int have = label.getWidth();
            want(need <= have, "%s 没被裁（需要 %d，实际 %d）".formatted(what, need, have));
        }
    }

    private static void checkColorsFollowTheme() {
        applyTheme(true);
        CodePopupContent dark = newContent();
        Color darkBg = dark.getBackground();
        Color darkGutter = CodeDocument.gutterColor();
        applyTheme(false);
        CodePopupContent light = newContent();
        Color lightBg = light.getBackground();
        Color lightGutter = CodeDocument.gutterColor();

        want(!darkBg.equals(lightBg),
                "底色跟着主题走（dark=%s light=%s）".formatted(hex(darkBg), hex(lightBg)));
        want(luma(darkBg) < luma(lightBg), "深色主题的底确实更暗");
        // 行号色也要跟着翻转，不然浅色主题上会出现「浅底浅字」看不见
        want(luma(darkGutter) < luma(lightGutter),
                "行号色跟着主题翻转（dark=%s light=%s）".formatted(hex(darkGutter), hex(lightGutter)));
        want(Math.abs(luma(darkGutter) - luma(darkBg)) > 12,
                "深色下行号与底有足够对比（差 %.0f）".formatted(Math.abs(luma(darkGutter) - luma(darkBg))));

        // 兜底配色必须跟着背景明暗走：以前只有 Darcula 一套，
        // 浅色主题下方案取不到时，正文和提示行会糊在浅底上看不见。
        // 注意取色要在对应主题就位之后进行，CodeDocument 是现读 UIManager 的。
        applyTheme(true);
        for (String kind : new String[]{"keyword", "comment", "string", "metadata", "plain"}) {
            double diff = Math.abs(luma(CodeDocument.colorFor(kind)) - luma(darkBg));
            want(diff >= 25, "深色下 %s 与底色有对比（差 %.0f）".formatted(kind, diff));
        }
        double darkHint = Math.abs(luma(CodeDocument.hintColor()) - luma(darkBg));
        want(darkHint >= 30, "深色下提示行可读（差 %.0f）".formatted(darkHint));

        applyTheme(false);
        for (String kind : new String[]{"keyword", "comment", "string", "metadata", "plain"}) {
            double diff = Math.abs(luma(CodeDocument.colorFor(kind)) - luma(lightBg));
            want(diff >= 25, "浅色下 %s 与底色有对比（差 %.0f）".formatted(kind, diff));
        }
        double lightHint = Math.abs(luma(CodeDocument.hintColor()) - luma(lightBg));
        want(lightHint >= 30, "浅色下提示行可读（差 %.0f）".formatted(lightHint));
    }

    // ---------------- 渲染 ----------------

    private static void render(String out, boolean dark) throws Exception {
        CodePopupContent content = new CodePopupContent();
        content.setChapter(TITLE, BODY, 3, 413);
        content.setCodeFontSize(13);

        // 模拟宿主给弹出框的尺寸
        JPanel host = new JPanel(new BorderLayout());
        host.setBackground(dark ? new Color(0x1E, 0x1E, 0x1E) : new Color(0xDE, 0xDE, 0xDE));
        host.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        // 1px 外框：截图里能看清弹窗边界，方便比对尺寸
        content.setBorder(javax.swing.BorderFactory.createLineBorder(
                dark ? new Color(0x4E, 0x51, 0x54) : new Color(0xC9, 0xC9, 0xC9)));
        host.add(content, BorderLayout.CENTER);

        int w = 700;
        int h = 520;
        host.setSize(w, h);
        host.addNotify();
        host.validate();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        host.printAll(g);
        g.dispose();
        File f = new File(out);
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", f);
        System.out.println("-> " + out + "   h=" + hex(content.getBackground())
                + " 行号=" + hex(CodeDocument.gutterColor()));
    }

    // ---------------- 辅助 ----------------

    private static CodePopupContent newContent() {
        CodePopupContent content = new CodePopupContent();
        content.setChapter(TITLE, BODY, 3, 413);
        return content;
    }

    /** 放进有尺寸的宿主里走一遍布局，滚动条才有真实的 ext/vis 范围 */
    private static CodePopupContent laidOut(String title, String body, int index, int total) {
        CodePopupContent content = new CodePopupContent();
        content.setChapter(title, body, index, total);
        JPanel host = new JPanel(new FlowLayout(FlowLayout.LEFT));
        host.add(content);
        host.setSize(700, 520);
        host.addNotify();
        host.validate();
        content.setSize(content.getPreferredSize());
        content.doLayout();
        content.validate();
        return content;
    }

    private static void wheel(CodePopupContent content, int rotation, boolean ctrl) {
        int mods = ctrl ? InputEvent.CTRL_DOWN_MASK : 0;
        MouseWheelEvent e = new MouseWheelEvent(content.codePane(),
                MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), mods,
                20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation);
        content.codePane().dispatchEvent(e);
    }

    /** 只想听部分回调的 Nav 基类 */
    private static class SilentNav implements CodePopupContent.Nav {
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
    }

    private static void applyTheme(boolean dark) {
        Color panel = dark ? new Color(0x2B, 0x2B, 0x2B) : new Color(0xF7, 0xF7, 0xF7);
        Color fg = dark ? new Color(0xBB, 0xBB, 0xBB) : new Color(0x1E, 0x1E, 0x1E);
        Color dim = dark ? new Color(0x86, 0x86, 0x86) : new Color(0x77, 0x77, 0x77);
        UIManager.put("Panel.background", panel);
        UIManager.put("Label.foreground", fg);
        UIManager.put("Label.disabledForeground", dim);
        // 探针不重建组件：CodeDocument 取色时是现读 UIManager 的，所以换主题立刻生效
    }

    /** 亮度，只用于断言「深色这边确实更暗」 */
    private static double luma(Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
    }

    private static String sampleBody() {
        return """
                　　他推开门的时候，屋里的灯还亮着。
                桌上摊着一封信，字迹潦草，落款处被人用指甲划掉了。
                　　他在扉页上看到一行小字：注释结束符 */ 混在正文里会出事。
                　　他看了很久，久到窗外的雨停了又下，下了又停。
                　　最后他把信折好，塞进外套内袋，像收起一件已经用不上的武器。
                """.strip();
    }

    private static String longBody() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("　　第 ").append(i).append(" 段：这是一段用来把内容撑长的文字，"
                    + "好让滚动条真的需要滚动起来，不然滚轮测试测的是空气。\n");
        }
        return sb.toString();
    }

    private static String abbrev(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** 把前几行连同行号列一起打出来，用于定位「空行没行号」这类问题 */
    private static String dump(String[] lines, int gutter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            sb.append("\n     [").append(i + 1).append("] 「").append(lines[i]).append("」");
        }
        return sb.toString();
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static void want(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private static void fail(String what) {
        System.out.println("  FAIL " + what);
        failed++;
    }
}
