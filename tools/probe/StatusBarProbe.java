import com.github.ideanovel.ui.ChapterChunks;
import com.github.ideanovel.ui.ChunkCursor;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 状态栏阅读探针：ChunkCursor 的游标语义 + 状态栏条带渲染截图。
 *
 * widget 本体（StatusBarChunkWidgetFactory）全是平台类，离线创建不了；
 * 能离线验证的都拆在这里：段游标的越界/跳尾/进度文本，
 * 以及状态栏上那一小条长什么样（深浅主题各一张）。
 */
public final class StatusBarProbe {

    private static final String BODY = """
            　　他推开门的时候，屋里的灯还亮着。桌上摊着一封信，字迹潦草，落款处被人用指甲划掉了。
            　　他看了很久，久到窗外的雨停了又下，下了又停。最后他把信折好，塞进外套内袋。
            　　短段。
            """.strip();

    private static int failed;

    public static void main(String[] args) throws Exception {
        checkCursor();
        saveShots();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS" : "FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void checkCursor() {
        System.out.println();
        System.out.println("== ChunkCursor 游标语义");

        ChunkCursor cursor = new ChunkCursor();
        cursor.setChapter(BODY, false);
        int total = cursor.size();
        want(total == 3, "样例切出 3 段（实际 %d）".formatted(total));
        want(cursor.index() == 0, "初始在第 1 段");

        want(cursor.display().endsWith("(1/3)"), "进度尾巴格式（%s）"
                .formatted(cursor.display().substring(Math.max(0, cursor.display().length() - 8))));

        // 逐段往后：中途回 true，最后一段再往后回 false
        want(cursor.next(), "中段往后返回 true");
        want(cursor.index() == 1, "翻到第 2 段");
        want(cursor.next(), "末段往前一步仍返回 true");
        want(cursor.atLast(), "现在在最后一段");
        want(!cursor.next(), "最后一段再往后返回 false（越章信号）");

        // 跳尾落点：从下一章往回翻时应落在最后一段
        ChunkCursor back = new ChunkCursor();
        back.setChapter(BODY, true);
        want(back.index() == total - 1, "jumpToEnd 落在最后一段（%d/%d）"
                .formatted(back.index() + 1, total));
        while (back.prev()) {
            // 一路翻回第一段
        }
        want(back.atFirst(), "连续往前能回到第 1 段");
        want(!back.prev(), "第 1 段再往前返回 false（越章信号）");

        // 空正文不炸，进度仍是合法格式
        ChunkCursor empty = new ChunkCursor();
        empty.setChapter("", false);
        want(empty.size() == 1 && empty.display().endsWith("(1/1)"), "空正文算 1 段");

        // 占位文案走同一条路径
        ChunkCursor placeholder = new ChunkCursor();
        want(placeholder.displayPlaceholder("摸鱼小说 · 未打开").contains("未打开"), "占位文案可读");
    }

    // ---------------- 截图 ----------------

    private static void saveShots() throws IOException {
        File dir = new File("docs/screenshots");
        dir.mkdirs();

        ChunkCursor cursor = new ChunkCursor();
        cursor.setChapter(BODY, false);
        cursor.next(); // 展示中间一段，进度 (2/3) 更有代表性

        writeShot(cursor.display(), new Color(0x2B, 0x2B, 0x2B),
                new Color(0xBC, 0xBC, 0xBC), new File(dir, "statusbar-dark.png"));
        writeShot(cursor.display(), new Color(0xF7, 0xF7, 0xF7),
                new Color(0x1E, 0x1E, 0x1E), new File(dir, "statusbar-light.png"));

        System.out.println("  截图已写入 docs/screenshots/statusbar-{dark,light}.png");
    }

    /** 模拟状态栏左下角那一小条：底色 + 一行文字，宽度按典型状态栏给 */
    private static void writeShot(String text, Color bg, Color fg, File out) throws IOException {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(bg);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));

        JLabel label = new JLabel(text);
        label.setForeground(fg);
        label.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        bar.add(label, BorderLayout.CENTER);

        bar.setSize(560, 22);
        bar.doLayout();
        Dimension size = bar.getSize();
        BufferedImage img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        bar.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ---------------- 小工具 ----------------

    private static void want(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }
}
