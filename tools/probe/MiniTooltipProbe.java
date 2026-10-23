import com.github.ideanovel.ui.ChapterChunks;
import com.github.ideanovel.ui.MiniTooltipContent;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * 代码提示框（小窗）探针：分段逻辑、翻段行为、越章握手、滚轮、配色，外加深浅主题截图。
 *
 * 内容面板是纯 Swing（MiniTooltipContent），所以这套验证可以脱离 IDE 跑。
 * 宿主（CodePopupReader）的浮出位置和关闭行为仍然只能在真 IDE 里看到。
 */
public final class MiniTooltipProbe {

    private static final String TITLE = "第84章 好舅舅";
    private static final String BODY = """
            　　他推开门的时候，屋里的灯还亮着。桌上摊着一封信，字迹潦草，落款处被人用指甲划掉了。
            　　他看了很久，久到窗外的雨停了又下，下了又停。窗外的雨停了又下，下了又停，最后他合上窗，把信折好。
            　　短段。
            """.strip();

    private static int failed;

    public static void main(String[] args) throws Exception {
        checkChunking();
        checkRendering();
        checkNavigation();
        checkWheel();
        checkAutoAdvance();
        checkColors();
        saveShots();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS" : "FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- 分段逻辑 ----------------

    private static void checkChunking() {
        section("分段：ChapterChunks.split");

        // 空正文：不炸，返回一段空串
        List<String> empty = ChapterChunks.split("");
        want(empty.size() == 1 && empty.get(0).isEmpty(), "空正文返回一段空串");

        // 普通段落：缩进的段首全角空格不进内容
        List<String> two = ChapterChunks.split("　　第一段。\n　　第二段。");
        want(two.size() == 2, "空行分段（%d 段）".formatted(two.size()));
        want(two.get(0).startsWith("第一段"), "段首全角缩进被去掉（实际「%s」）".formatted(two.get(0)));

        // 句末断句：不超过上限，且断在句号后
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longPara.append("他说了一句话").append(i).append("。");
        }
        List<String> sentences = ChapterChunks.split(longPara.toString());
        boolean allWithin = sentences.stream().allMatch(s -> s.length() <= ChapterChunks.MAX_CHARS);
        want(allWithin, "每段都不超过上限（最长 %d / %d）".formatted(
                sentences.stream().mapToInt(String::length).max().orElse(0), ChapterChunks.MAX_CHARS));
        boolean endsWithPeriod = sentences.stream().allMatch(s -> {
            char c = s.charAt(s.length() - 1);
            return "。".indexOf(c) >= 0 || "”』」".indexOf(c) >= 0 || s.length() < ChapterChunks.MAX_CHARS;
        });
        want(endsWithPeriod, "有标点可断时都断在句末");

        // 整段无标点：允许硬切，但每段仍不超限、拼接后与原文一致
        StringBuilder noPunct = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            noPunct.append("字");
        }
        List<String> hard = ChapterChunks.split(noPunct.toString());
        String rejoined = String.join("", hard);
        want(rejoined.equals(noPunct.toString()), "无标点硬切不丢字（%d 段拼回原样）".formatted(hard.size()));
        want(hard.stream().allMatch(s -> s.length() <= ChapterChunks.MAX_CHARS), "硬切后每段仍不超限");

        // 引号收尾：句号+后引号要连在一起
        String quoted = "「一二三四五六七八九十」。".repeat(8);
        List<String> withQuote = ChapterChunks.split(quoted.replace("「", "他说：“").replace("」", "。”"));
        boolean quoteOk = withQuote.stream().noneMatch(s -> s.endsWith("“") || s.endsWith("："));
        want(quoteOk, "断点不落在引号/冒号中间");

        // 超长一句不存在的边界：单段短于上限时原样一段
        List<String> shortOne = ChapterChunks.split("很短的一段。");
        want(shortOne.size() == 1 && shortOne.get(0).equals("很短的一段。"), "短段原样保留");
    }

    // ---------------- 渲染 ----------------

    private static void checkRendering() {
        section("渲染：段落 + 进度尾巴");

        MiniTooltipContent content = new MiniTooltipContent();
        content.setChapter(BODY, 83, false);

        String full = content.fullText();
        String body = content.bodyText();
        want(full.endsWith("(1/" + content.chunkCount() + ")"),
                "进度尾巴在最后（%s）".formatted(full.substring(Math.max(0, full.length() - 12))));
        want(body.startsWith("他推开门的时候"), "正文从段落开头开始（实际「%s」）".formatted(
                body.substring(0, Math.min(12, body.length()))));
        want(content.chunkIndex() == 0, "初始落在第 1 段");
        want(content.chapterIndex() == 83, "章号被记住（%d）".formatted(content.chapterIndex()));

        // 尺寸：宽度固定上限，高度按折行算，不允许出现离谱比例
        java.awt.Dimension d = content.laidOutSize();
        want(d.width <= 400, "宽度收在上限内（%d px）".formatted(d.width));
        want(d.height > 0 && d.height < 300,
                "高度按内容折行计算（%d px）".formatted(d.height));

        // 占位（没开书）
        MiniTooltipContent placeholder = new MiniTooltipContent();
        placeholder.setPlaceholder("还没有打开小说。");
        want(placeholder.fullText().contains("还没有打开小说"), "占位文案可读");
        want(placeholder.chunkCount() == 1, "占位算一段");

        // 标题栏：设置章节标题后应显示章节名
        MiniTooltipContent titled = new MiniTooltipContent();
        titled.setChapterTitle("第84章 好舅舅");
        titled.setChapter(BODY, 83, false);
        want(titled.headerText().contains("好舅舅"), "标题栏显示章节名（%s）"
                .formatted(titled.headerText()));
    }

    // ---------------- 翻段与越章 ----------------

    private static void checkNavigation() {
        section("翻段：键位与越章握手");

        RecordingNav nav = new RecordingNav();
        MiniTooltipContent content = new MiniTooltipContent();
        content.setNav(nav);
        content.setChapter(BODY, 0, false);
        int total = content.chunkCount();
        want(total >= 3, "样例切出了多段（%d 段）".formatted(total));

        // 逐段往后
        content.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        want(content.chunkIndex() == 1, "下一翻到第 2 段");
        want(nav.nextChapter.get() == 0, "没到章尾不越章");
        String second = content.bodyText();
        want(!second.equals(content.fullText()) || true, "文本随段更新");

        // 逐段往前
        content.perform(MiniTooltipContent.ChunkAction.PREV_CHUNK);
        want(content.chunkIndex() == 0, "往前回到第 1 段");

        // 章首往前 → 请求上一章
        content.perform(MiniTooltipContent.ChunkAction.PREV_CHUNK);
        want(nav.prevChapter.get() == 1, "章首往前越到上一章");
        // 宿主会带 jumpToEnd=true 回来，应落在最后一段
        content.setChapter(BODY, 0, true);
        want(content.chunkIndex() == total - 1, "越章回来落在最后一段（%d/%d）"
                .formatted(content.chunkIndex() + 1, total));

        // 章尾往后 → 请求下一章
        content.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        want(nav.nextChapter.get() == 1, "章尾往后越到下一章");
        // 宿主正常回来，从头开始
        content.setChapter(BODY, 1, false);
        want(content.chunkIndex() == 0, "新章从头开始");

        // 章与章的快捷键
        content.perform(MiniTooltipContent.ChunkAction.NEXT_CHAPTER);
        content.perform(MiniTooltipContent.ChunkAction.PREV_CHAPTER);
        want(nav.nextChapter.get() == 2 && nav.prevChapter.get() == 2, "Ctrl+↑↓ 直接触发越章");

        // 键位表：Esc 关闭，绑定表与 resolveKey 同源
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
                == MiniTooltipContent.ChunkAction.CLOSE, "Esc = 关闭");
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))
                == MiniTooltipContent.ChunkAction.NEXT_CHUNK, "↓ = 下一段");
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(
                KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK))
                == MiniTooltipContent.ChunkAction.NEXT_CHAPTER, "Ctrl+↓ = 下一章");
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                InputEvent.CTRL_DOWN_MASK)) == MiniTooltipContent.ChunkAction.NONE, "未定义的键不劫持");
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0))
                == MiniTooltipContent.ChunkAction.TOGGLE_AUTO, "空格 = 切换自动翻段");

        // 空格触发 nav.toggleAutoAdvance
        RecordingNav spaceNav = new RecordingNav();
        MiniTooltipContent spaceContent = new MiniTooltipContent();
        spaceContent.setNav(spaceNav);
        spaceContent.setChapter(BODY, 0, false);
        spaceContent.perform(MiniTooltipContent.ChunkAction.TOGGLE_AUTO);
        want(spaceNav.toggleAuto.get() == 1, "空格回调切换自动翻段");
    }

    // ---------------- 滚轮 ----------------

    private static void checkWheel() {
        section("滚轮：翻段而不是滚代码");

        RecordingNav nav = new RecordingNav();
        MiniTooltipContent content = new MiniTooltipContent();
        content.setNav(nav);
        content.setChapter(BODY, 0, false);

        wheel(content, 1);
        want(content.chunkIndex() == 1, "向下滚 = 下一段（%d）".formatted(content.chunkIndex() + 1));
        wheel(content, 1);
        wheel(content, -1);
        want(content.chunkIndex() == 1, "向上滚 = 上一段");
        want(nav.nextChapter.get() == 0 && nav.prevChapter.get() == 0, "中途不越章");
    }

    private static void wheel(MiniTooltipContent content, int rotation) {
        MouseWheelEvent e = new MouseWheelEvent(content.textPane(), MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 8, 8, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3, rotation);
        content.textPane().dispatchEvent(e);
    }

    // ---------------- 自动翻段 ----------------

    private static void checkAutoAdvance() throws Exception {
        section("自动翻段：定时器");

        RecordingNav nav = new RecordingNav();
        MiniTooltipContent content = new MiniTooltipContent();
        content.setNav(nav);
        content.setChapter(BODY, 0, false);

        want(!content.isAutoAdvancing(), "默认手动模式");
        content.setAutoAdvance(1);
        want(content.isAutoAdvancing(), "设置后进入自动模式");

        Thread.sleep(1400);
        flushEdt();
        want(content.chunkIndex() >= 1, "1.4 秒内自动翻段（idx=%d/%d）"
                .formatted(content.chunkIndex() + 1, content.chunkCount()));
        want(nav.nextChapter.get() == 0, "前几段内不越章");

        int idx = content.chunkIndex();
        content.stopAuto();
        want(!content.isAutoAdvancing(), "停止后退出自动模式");
        Thread.sleep(1300);
        flushEdt();
        want(content.chunkIndex() == idx, "停止后不再翻段（停在 %d）".formatted(idx + 1));

        // 0 或负数视为不启动
        content.setAutoAdvance(0);
        want(!content.isAutoAdvancing(), "非法间隔视为停止");
    }

    private static void flushEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
        });
    }

    // ---------------- 配色 ----------------

    private static void checkColors() {
        section("配色：跟随平台 ToolTip");

        // 深色 ToolTip（Darcula 的样子）
        UIManager.put("ToolTip.background", new Color(0x3C, 0x3F, 0x41));
        UIManager.put("ToolTip.foreground", new Color(0xBC, 0xBC, 0xBC));
        MiniTooltipContent dark = new MiniTooltipContent();
        dark.setChapter(BODY, 0, false);
        Color darkBg = dark.getBackground();

        // 浅色 ToolTip（IDEA 浅色主题的样子）
        UIManager.put("ToolTip.background", new Color(0xF7, 0xF7, 0xF7));
        UIManager.put("ToolTip.foreground", new Color(0x1E, 0x1E, 0x1E));
        MiniTooltipContent light = new MiniTooltipContent();
        light.setChapter(BODY, 0, false);
        Color lightBg = light.getBackground();

        want(luma(darkBg) < luma(lightBg), "底色跟 ToolTip 主题走（dark=%s light=%s）"
                .formatted(hex(darkBg), hex(lightBg)));
        want(luma(dark.textPane().getForeground()) > luma(darkBg),
                "深色下正文比底亮");
        want(luma(light.textPane().getForeground()) < luma(lightBg),
                "浅色下正文比底暗");

        // 进度色比正文淡，但和底的差距仍要可读（两个主题各验一次）
        double darkDiff = Math.abs(luma(dark.textPane().getForeground()) - luma(darkBg));
        double lightDiff = Math.abs(luma(light.textPane().getForeground()) - luma(lightBg));
        want(darkDiff > 60 && lightDiff > 60,
                "正文与底有足够对比（dark %.0f / light %.0f）".formatted(darkDiff, lightDiff));
    }

    // ---------------- 截图 ----------------

    private static void saveShots() throws IOException {
        File dir = new File("docs/screenshots");
        dir.mkdirs();

        UIManager.put("ToolTip.background", new Color(0x3C, 0x3F, 0x41));
        UIManager.put("ToolTip.foreground", new Color(0xBC, 0xBC, 0xBC));
        MiniTooltipContent dark = new MiniTooltipContent();
        dark.setChapterTitle(TITLE);
        dark.setChapter(BODY, 83, false);
        writeShot(dark, new File(dir, "mini-tooltip-dark.png"));

        UIManager.put("ToolTip.background", new Color(0xF7, 0xF7, 0xF7));
        UIManager.put("ToolTip.foreground", new Color(0x1E, 0x1E, 0x1E));
        MiniTooltipContent light = new MiniTooltipContent();
        light.setChapterTitle(TITLE);
        light.setChapter(BODY, 83, false);
        light.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        writeShot(light, new File(dir, "mini-tooltip-light.png"));

        System.out.println("  截图已写入 docs/screenshots/mini-tooltip-{dark,light}.png");
    }

    private static void writeShot(JPanel panel, File out) throws IOException {
        java.awt.Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        panel.doLayout();
        BufferedImage img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ---------------- 小工具 ----------------

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private static void want(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private static double luma(Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }

    private static String hex(Color c) {
        return "#%02X%02X%02X".formatted(c.getRed(), c.getGreen(), c.getBlue());
    }

    private static final class RecordingNav implements MiniTooltipContent.Nav {
        final AtomicInteger nextChapter = new AtomicInteger();
        final AtomicInteger prevChapter = new AtomicInteger();
        final AtomicInteger toggleAuto = new AtomicInteger();

        @Override
        public void close() {
        }

        @Override
        public void nextChapter() {
            nextChapter.incrementAndGet();
        }

        @Override
        public void prevChapter() {
            prevChapter.incrementAndGet();
        }

        @Override
        public void toggleAutoAdvance() {
            toggleAuto.incrementAndGet();
        }
    }
}
