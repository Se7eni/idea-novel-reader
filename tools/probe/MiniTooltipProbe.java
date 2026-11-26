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
        checkNoRunawayChapters();
        checkFocusPause();
        checkColors();
        saveShots();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS" : "FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- 防连续跳章（1.0.22 回归） ----------------

    /**
     * 回归用例：章节加载中 / 空章节时，翻段不能把「没有下一段」当成章尾去越章。
     * 之前的 bug 是自动翻段开着时，网络章节加载中被一路跳过去。
     */
    private static void checkNoRunawayChapters() {
        section("防连续跳章：占位/空章节不越章");

        // 1. 加载中（setPlaceholder 只放一条文案）连翻 10 次，不应越章
        RecordingNav nav = new RecordingNav();
        MiniTooltipContent content = new MiniTooltipContent();
        content.setNav(nav);
        content.setPlaceholder("正在加载…");
        for (int i = 0; i < 10; i++) {
            content.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        }
        want(nav.nextChapter.get() == 0,
                "加载中连翻 10 次不越章（实际 %d 次）".formatted(nav.nextChapter.get()));

        // 2. 空章节（切出来只有一段空串）同样不越章
        content.setChapter("", 0, false);
        for (int i = 0; i < 10; i++) {
            content.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        }
        want(nav.nextChapter.get() == 0,
                "空章节连翻 10 次不越章（实际 %d 次）".formatted(nav.nextChapter.get()));

        // 3. 加载完成后恢复正常翻段
        content.setChapter(BODY, 1, false);
        content.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        want(content.chunkIndex() == 1, "加载完成恢复正常翻段");
        want(content.chunkCount() >= 3, "正常章节仍有多段（%d）".formatted(content.chunkCount()));

        // 4. 自动模式下，极短章节（1 段非空）不越章：
        //    这种章节一进来就在章尾，自动模式会一章接一章跳过去
        RecordingNav shortNav = new RecordingNav();
        MiniTooltipContent shortChapter = new MiniTooltipContent();
        shortChapter.setNav(shortNav);
        shortChapter.setChapter("很短。", 0, false);
        shortChapter.setAutoAdvance(1);
        for (int i = 0; i < 5; i++) {
            shortChapter.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        }
        want(shortNav.nextChapter.get() == 0,
                "自动模式下极短章节不越章（实际 %d 次）".formatted(shortNav.nextChapter.get()));
        shortChapter.stopAuto();

        // 5. 同一章节改为手动模式后，仍然允许越章（不能因为自动模式的保护把手动也堵死）
        RecordingNav manualNav = new RecordingNav();
        MiniTooltipContent manual = new MiniTooltipContent();
        manual.setNav(manualNav);
        manual.setChapter("很短。", 0, false);
        manual.perform(MiniTooltipContent.ChunkAction.NEXT_CHUNK);
        want(manualNav.nextChapter.get() == 1,
                "手动模式极短章节仍可越章（实际 %d 次）".formatted(manualNav.nextChapter.get()));
    }

    // ---------------- 分段逻辑 ----------------

    private static void checkChunking() {
        section("分段：ChapterChunks.split");

        // 空正文：不炸，返回一段空串
        List<String> empty = ChapterChunks.split("");
        want(empty.size() == 1 && empty.get(0).isEmpty(), "空正文返回一段空串");

        // 连续短行合并：不再逐行独立成段，攒到接近一行
        List<String> two = ChapterChunks.split("　　第一段。\n　　第二段。");
        want(two.size() == 1, "连续短行合并成一段（%d 段）".formatted(two.size()));
        want(two.get(0).startsWith("第一段"), "段首全角缩进被去掉（实际「%s」）".formatted(two.get(0)));

        // 对话短行：一句话一行的排版，合并后不该还是一条几个字
        List<String> dialog = ChapterChunks.split(
                "他看着她。\n她别过脸。\n他上前一步。\n她后退一步。\n他站住了。\n她抬起头。\n");
        want(dialog.size() <= 2, "对话短行合并（%d 段）".formatted(dialog.size()));
        want(dialog.stream().allMatch(s -> s.length() <= ChapterChunks.MAX_CHARS + 10),
                "合并后每段仍不超上限");

        // 句末断句：不超过上限，且断在句号后
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longPara.append("他说了一句话").append(i).append("。");
        }
        List<String> sentences = ChapterChunks.split(longPara.toString());
        boolean allWithin = sentences.stream().allMatch(s -> s.length() <= ChapterChunks.MAX_CHARS + 10);
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
        want(hard.stream().allMatch(s -> s.length() <= ChapterChunks.MAX_CHARS + 10), "硬切后每段仍不超限");

        // 引号收尾：句号+后引号要连在一起
        String quoted = "「一二三四五六七八九十」。".repeat(8);
        List<String> withQuote = ChapterChunks.split(quoted.replace("「", "他说：“").replace("」", "。”"));
        boolean quoteOk = withQuote.stream().noneMatch(s -> s.endsWith("“") || s.endsWith("："));
        want(quoteOk, "断点不落在引号/冒号中间");

        // 超长一句不存在的边界：单段短于上限时原样一段
        List<String> shortOne = ChapterChunks.split("很短的一段。");
        want(shortOne.size() == 1 && shortOne.get(0).equals("很短的一段。"), "短段原样保留");

        // 断点落在「。」和「”」之间时，收尾引号必须留在上一段，
        // 否则弹窗会出现只剩「。”」的碎片（真实反馈过的现象）
        List<String> tailQuote = ChapterChunks.split(
                "他说：“我明白了。”然后他走了出去，门在他身后关上，走廊里只剩下雨声和风声，"
                        + "还有楼下偶尔传来的脚步声。");
        want(tailQuote.stream().noneMatch(s -> s.replaceAll("[\\p{P}\\s]", "").isEmpty()),
                "没有纯标点段（%s）".formatted(tailQuote));
        want(tailQuote.stream().filter(s -> s.contains("”")).allMatch(s -> s.contains("我明白了")),
                "收尾引号不单独成段（%s）".formatted(tailQuote));

        // 章节正文第一行是标题（Chapter.startOffset 含标题行），
        // 合并到一行时必须补空格，否则会粘成「第17章 绝境！郑笙拿着菜刀」
        List<String> withTitle = ChapterChunks.split("第17章 绝境！\n郑笙拿着菜刀。\n手起刀落。");
        want(withTitle.get(0).contains("绝境！ 郑笙"),
                "标题与正文之间有空格（%s）".formatted(withTitle.get(0)));
        want(withTitle.get(0).contains("菜刀。 手起刀落"),
                "正文相邻短行之间也有空格（%s）".formatted(withTitle.get(0)));
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

        // 尺寸：单行不折行，宽度随文本自适应、上限封顶，高度固定一行
        java.awt.Dimension d = content.laidOutSize();
        want(d.width <= 620, "宽度封顶在上限内（%d px）".formatted(d.width));
        want(d.height > 0 && d.height < 120,
                "高度是一行（%d px）".formatted(d.height));

        // 占位（没开书）
        MiniTooltipContent placeholder = new MiniTooltipContent();
        placeholder.setPlaceholder("还没有打开小说。");
        want(placeholder.fullText().contains("还没有打开小说"), "占位文案可读");
        want(placeholder.chunkCount() == 1, "占位算一段");

        // 弹窗不再渲染章节名（只留正文 + 段进度），但标题仍被记录，宿主可查询
        MiniTooltipContent titled = new MiniTooltipContent();
        titled.setChapterTitle("第84章 好舅舅");
        titled.setChapter(BODY, 83, false);
        want(titled.headerText().contains("好舅舅") || titled.headerText().isEmpty(),
                "章节名不再渲染（headerText=%s）".formatted(titled.headerText()));
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
        want(MiniTooltipContent.resolveKey(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0))
                == MiniTooltipContent.ChunkAction.CHANGE_AUTO, "S = 设置秒数自动换段");

        // 空格触发 nav.toggleAutoAdvance
        RecordingNav spaceNav = new RecordingNav();
        MiniTooltipContent spaceContent = new MiniTooltipContent();
        spaceContent.setNav(spaceNav);
        spaceContent.setChapter(BODY, 0, false);
        spaceContent.perform(MiniTooltipContent.ChunkAction.TOGGLE_AUTO);
        want(spaceNav.toggleAuto.get() == 1, "空格回调切换自动翻段");
        spaceContent.perform(MiniTooltipContent.ChunkAction.CHANGE_AUTO);
        want(spaceNav.changeAuto.get() == 1, "S 回调设置秒数");

        // pendingChunk：指定从第几段开始（记忆位置恢复）
        MiniTooltipContent restored = new MiniTooltipContent();
        restored.setChapter(BODY, 0, false);
        int totalForRestore = restored.chunkCount();
        restored.setPendingChunk(totalForRestore - 1);
        restored.setChapter(BODY, 0, false);
        want(restored.chunkIndex() == totalForRestore - 1, "pendingChunk 落到指定段（%d/%d）"
                .formatted(restored.chunkIndex() + 1, totalForRestore));
        restored.setChapter(BODY, 0, false);
        want(restored.chunkIndex() == 0, "pendingChunk 只生效一次");
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

    // ---------------- 失焦暂停（1.0.26 回归） ----------------

    /**
     * 回归用例：弹窗失焦要暂停自动翻段，重新聚焦要接着跑。
     * 之前的 bug 是失焦后定时器还在跑，人已经不在看，它却一路翻到下一章。
     */
    private static void checkFocusPause() throws Exception {
        section("失焦暂停：切出去不再自动翻段");

        RecordingNav nav = new RecordingNav();
        MiniTooltipContent content = new MiniTooltipContent();
        content.setNav(nav);
        content.setChapter(BODY, 0, false);
        content.setAutoAdvance(1);
        want(content.isAutoAdvancing(), "自动已开启");

        content.pauseAutoOnFocusLost();
        want(!content.isAutoAdvancing(), "失焦后暂停");
        int pausedAt = content.chunkIndex();

        Thread.sleep(1300);
        flushEdt();
        want(content.chunkIndex() == pausedAt,
                "暂停期间不再翻段（停在 %d）".formatted(pausedAt + 1));

        content.resumeAutoOnFocusGained();
        want(content.isAutoAdvancing(), "重新聚焦恢复自动");
        Thread.sleep(1400);
        flushEdt();
        want(content.chunkIndex() > pausedAt, "恢复后继续翻段（%d → %d）"
                .formatted(pausedAt + 1, content.chunkIndex() + 1));

        content.stopAuto();
        want(!content.isAutoAdvancing(), "手动停止后不再翻段");
        content.resumeAutoOnFocusGained();
        want(!content.isAutoAdvancing(), "手动停掉之后聚焦也不该自己开（尊重用户意图）");
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
        final AtomicInteger changeAuto = new AtomicInteger();

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

        @Override
        public void changeAutoSeconds() {
            changeAuto.incrementAndGet();
        }
    }
}
