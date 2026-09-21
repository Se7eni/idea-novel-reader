import com.github.ideanovel.model.ReadingProgress;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.ui.ShelfPopup;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** 把书架和工具栏真实渲染成 PNG，用来肉眼确认美化效果。 */
public class UiProbe {

    public static void main(String[] args) throws Exception {
        applyTheme(true);
        System.out.println("dark  PopupMenu.background = " + UIManager.getColor("PopupMenu.background")
                + " / Label.foreground = " + UIManager.getColor("Label.foreground"));
        renderShelf("docs/screenshots/shelf-dark.png", true);
        renderToolbar("docs/screenshots/toolbar-dark.png", true);
        applyTheme(false);
        System.out.println("light PopupMenu.background = " + UIManager.getColor("PopupMenu.background")
                + " / Label.foreground = " + UIManager.getColor("Label.foreground"));
        renderShelf("docs/screenshots/shelf-light.png", false);
        renderToolbar("docs/screenshots/toolbar-light.png", false);
    }

    /**
     * 用 IDEA 深/浅色的典型配色，UIManager 取色才有意义。
     *
     * 注意：不能指望 UIManager.put("PopupMenu.background", ...) 生效 ——
     * Metal LAF 的 PopupMenuUI 会用它自己的默认值盖掉，改多少都一样。
     * 所以这里额外塞一组探针专用的 key，被测代码从 UIManager 取的就是这些。
     */
    private static void applyTheme(boolean dark) {
        Color panel, popup, fgColor, dim;
        if (dark) {
            panel = new Color(0x3C, 0x3F, 0x41);
            popup = new Color(0x3C, 0x3F, 0x41);
            fgColor = new Color(0xBB, 0xBB, 0xBB);
            dim = new Color(0x86, 0x86, 0x86);
        } else {
            panel = new Color(0xF7, 0xF7, 0xF7);
            popup = new Color(0xF7, 0xF7, 0xF7);
            fgColor = new Color(0x1E, 0x1E, 0x1E);
            dim = new Color(0x77, 0x77, 0x77);
        }
        UIManager.put("Panel.background", panel);
        UIManager.put("PopupMenu.background", popup);
        UIManager.put("Label.foreground", fgColor);
        UIManager.put("Label.disabledForeground", dim);
    }

    /** 造几条记录，不碰 NovelSettingsState（IDE 之外拿不到 ApplicationManager） */
    private static java.util.List<ReadingProgress> seed(int n) {
        long now = System.currentTimeMillis();
        String[] titles = {
                "我真没想重生啊", "我在惊悚游戏里封神（无限）",
                "诡秘之主", "长夜余火", "宿命之环",
                "非常长的书名用来测试截断效果到底行不行啊喂",
                "旧日之箓", "深海余烬", "我们生活在南京",
        };
        long[] ago = {2 * 60000L, 45 * 60000L, 3 * 3600_000L, 26 * 3600_000L,
                5 * 86400_000L, 40 * 86400_000L, 400 * 86400_000L, 0, 12 * 60_000L};
        java.util.List<ReadingProgress> out = new ArrayList<>();
        for (int i = 0; i < n && i < titles.length; i++) {
            ReadingProgress p = new ReadingProgress("book-" + i, titles[i],
                    i % 3 == 0 ? SourceType.REMOTE : SourceType.LOCAL,
                    "C:/novels/" + titles[i] + ".txt");
            p.chapterIndex = 750 + i * 17;
            p.updatedAt = now - ago[i];
            out.add(p);
        }
        return out;
    }

    /** 内存版书架数据，够渲染用 */
    private static ShelfPopup.BookStore fakeStore(java.util.List<ReadingProgress> data) {
        return new ShelfPopup.BookStore() {
            @Override
            public java.util.List<ReadingProgress> recent() {
                return new ArrayList<>(data);
            }

            @Override
            public boolean remove(String bookId) {
                return data.removeIf(p -> bookId.equals(p.bookId));
            }

            @Override
            public int clear() {
                int n = data.size();
                data.clear();
                return n;
            }
        };
    }

    private static void renderShelf(String out, boolean dark) throws Exception {
        // 第 2 本设成当前在读（带「当前」徽标），另外把两行拨到悬停/确认态
        List<ReadingProgress> data = seed(5);
        ShelfPopup popup = new ShelfPopup("book-1", p -> { }, () -> { }, fakeStore(data));
        popup.reload();

        // JPopupMenu 是给别人 show() 用的，直接 printAll 它是空白的
        // （它把内容画在轻量级弹窗窗口上，不在自己的绘制路径里）。
        // 所以把内容剥出来放进一个普通 JPanel 再渲染。
        JPanel content = new JPanel(new java.awt.BorderLayout());
        content.setBackground(javax.swing.UIManager.getColor("PopupMenu.background"));
        content.setBorder(javax.swing.BorderFactory.createLineBorder(
                dark ? new Color(0x4E, 0x51, 0x54) : new Color(0xC9, 0xC9, 0xC9)));
        while (popup.getComponentCount() > 0) {
            Component c = popup.getComponent(0);
            popup.remove(0);
            content.add(c, content.getComponentCount() == 0
                    ? java.awt.BorderLayout.CENTER : java.awt.BorderLayout.SOUTH);
        }
        // 行内的颜色是构造时算好的，这里显式重取一次，深浅两张图才有区别。
        // 真实场景由 JPopupMenu.updateUI 在切主题时触发。
        List<Component> rows = findRows(content);
        for (Component row : rows) {
            java.lang.reflect.Method m = row.getClass().getDeclaredMethod("applyTheme");
            m.setAccessible(true);
            m.invoke(row);
        }
        setRowHot(rows.get(1));
        armDelete(rows.get(3));
        content.setPreferredSize(new Dimension(320, content.getPreferredSize().height));

        JPanel host = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 20));
        host.setBackground(dark ? new Color(0x2B, 0x2B, 0x2B) : new Color(0xDE, 0xDE, 0xDE));
        host.add(content);

        host.setSize(420, 420);
        host.addNotify();
        host.validate();

        BufferedImage img = new BufferedImage(420, 420, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        host.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("-> " + out);
    }

    /** 通过反射把某一行拨到悬停态，ShelfRow.setHot 是私有的 */
    private static void setRowHot(Component row) throws Exception {
        java.lang.reflect.Method m = row.getClass().getDeclaredMethod("setHot", boolean.class);
        m.setAccessible(true);
        m.invoke(row, true);
    }

    private static void armDelete(Component row) throws Exception {
        java.lang.reflect.Field f = row.getClass().getDeclaredField("deleteButton");
        f.setAccessible(true);
        JButton button = (JButton) f.get(row);
        java.lang.reflect.Method setArmed =
                button.getClass().getDeclaredMethod("setArmed", boolean.class);
        setArmed.setAccessible(true);
        setArmed.invoke(button, true);
        button.setVisible(true);
    }

    /** 行数容器是第一个 listPanel，里面就是一行行 ShelfRow */
    private static List<Component> findRows(Component root) {
        List<Component> rows = new ArrayList<>();
        walk(root, rows);
        return rows;
    }

    private static void walk(Component c, List<Component> rows) {
        String n = c.getClass().getSimpleName();
        if (n.equals("ShelfRow")) {
            rows.add(c);
            return;
        }
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                walk(child, rows);
            }
        }
    }

    private static void addTextIcon(JPanel bar, java.lang.reflect.Method m,
                                    String label, java.lang.reflect.Method iconM) throws Exception {
        bar.add((JButton) m.invoke(null, label, label, iconM.invoke(null), (Runnable) () -> { }));
    }

    /** 渲染真实工具栏：直接反射调 ReaderPanel 的构建逻辑，确保看到的就是实际效果 */
    private static void renderToolbar(String out, boolean dark) throws Exception {
        Class<?> tb = Class.forName("com.github.ideanovel.ui.ToolbarButtons");
        java.lang.reflect.Method textWithIcon = tb.getDeclaredMethod("textWithIcon",
                String.class, String.class, javax.swing.Icon.class, Runnable.class);
        java.lang.reflect.Method icon = tb.getDeclaredMethod("icon", String.class, javax.swing.Icon.class, Runnable.class);
        java.lang.reflect.Method sep = tb.getDeclaredMethod("separator");
        for (java.lang.reflect.Method m : new java.lang.reflect.Method[]{textWithIcon, icon, sep}) {
            m.setAccessible(true);
        }
        Runnable noop = () -> { };

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        bar.setBackground(dark ? new Color(0x3C, 0x3F, 0x41) : new Color(0xF2, 0xF2, 0xF2));

        Class<?> ri = Class.forName("com.github.ideanovel.ui.ReaderIcons");
        java.lang.reflect.Method fileIcon = ri.getDeclaredMethod("fileIcon");
        java.lang.reflect.Method globeIcon = ri.getDeclaredMethod("globeIcon");
        java.lang.reflect.Method shelfIcon = ri.getDeclaredMethod("shelfIcon");
        java.lang.reflect.Method listIcon = ri.getDeclaredMethod("listIcon");
        java.lang.reflect.Method arrowIcon = ri.getDeclaredMethod("arrowIcon", boolean.class);
        java.lang.reflect.Method fontIcon = ri.getDeclaredMethod("fontIcon", boolean.class);
        java.lang.reflect.Method gearIcon = ri.getDeclaredMethod("gearIcon");
        java.lang.reflect.Method maskIcon = ri.getDeclaredMethod("maskIcon");
        java.lang.reflect.Method autoIcon = ri.getDeclaredMethod("autoScrollIcon");
        java.lang.reflect.Method pauseIcon = ri.getDeclaredMethod("pauseIcon");
        java.lang.reflect.Method[] all = {fileIcon, globeIcon, shelfIcon, listIcon, arrowIcon,
                fontIcon, gearIcon, maskIcon, autoIcon, pauseIcon};
        for (java.lang.reflect.Method m : all) {
            m.setAccessible(true);
        }

        // 第一行：导航组（图标+文字）
        addTextIcon(bar, textWithIcon, "打开", fileIcon);
        addTextIcon(bar, textWithIcon, "网络", globeIcon);
        addTextIcon(bar, textWithIcon, "书架", shelfIcon);
        addTextIcon(bar, textWithIcon, "目录", listIcon);
        bar.add((JComponent) sep.invoke(null));
        bar.add((JButton) icon.invoke(null, "上一章", arrowIcon.invoke(null, false), noop));
        bar.add((JButton) icon.invoke(null, "下一章", arrowIcon.invoke(null, true), noop));
        bar.add((JButton) icon.invoke(null, "自动滚动", autoIcon.invoke(null), noop));
        bar.add((JComponent) sep.invoke(null));
        bar.add((JButton) icon.invoke(null, "缩小", fontIcon.invoke(null, true), noop));
        bar.add((JButton) icon.invoke(null, "放大", fontIcon.invoke(null, false), noop));
        bar.add((JButton) icon.invoke(null, "设置", gearIcon.invoke(null), noop));
        bar.add((JButton) icon.invoke(null, "隐身", maskIcon.invoke(null), noop));

        // 第二张：纯图标模式（窄栏时）
        JPanel bar2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        bar2.setBackground(bar.getBackground());
        bar2.add((JButton) icon.invoke(null, "打开", fileIcon.invoke(null), noop));
        bar2.add((JButton) icon.invoke(null, "网络", globeIcon.invoke(null), noop));
        bar2.add((JButton) icon.invoke(null, "书架", shelfIcon.invoke(null), noop));
        bar2.add((JButton) icon.invoke(null, "目录", listIcon.invoke(null), noop));
        bar2.add((JComponent) sep.invoke(null));
        bar2.add((JButton) icon.invoke(null, "上一章", arrowIcon.invoke(null, false), noop));
        bar2.add((JButton) icon.invoke(null, "下一章", arrowIcon.invoke(null, true), noop));
        // 悬停态：看看 hover 底色
        JButton hovered = (JButton) icon.invoke(null, "暂停", pauseIcon.invoke(null), noop);
        hovered.getModel().setRollover(true);
        bar2.add(hovered);
        bar2.add((JComponent) sep.invoke(null));
        bar2.add((JButton) icon.invoke(null, "缩小", fontIcon.invoke(null, true), noop));
        bar2.add((JButton) icon.invoke(null, "放大", fontIcon.invoke(null, false), noop));
        bar2.add((JButton) icon.invoke(null, "设置", gearIcon.invoke(null), noop));
        bar2.add((JButton) icon.invoke(null, "隐身", maskIcon.invoke(null), noop));

        // 用 BorderLayout 而不是 BoxLayout：BoxLayout(Y_AXIS) 会让子组件保持
        // 自己的 preferredSize 宽度，从这里加宽 host 并不会加宽 bar，
        // 结果就是文字被裁成「…」，看着像按钮本身有问题。
        JPanel host = new JPanel(new java.awt.BorderLayout());
        host.setBackground(bar.getBackground());
        host.add(bar, java.awt.BorderLayout.NORTH);
        host.add(bar2, java.awt.BorderLayout.CENTER);
        int w = 620;
        host.setSize(w, 76);
        host.addNotify();
        host.validate();
        BufferedImage img = new BufferedImage(w, 76, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        host.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("-> " + out);
    }
}
