package com.github.ideanovel.ui;

import com.github.ideanovel.model.ReadingProgress;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.settings.NovelSettingsState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 最近书架：每本书一行，显示书名、类型、读到第几章、多久以前读的。
 *
 * 两个交互上的考虑：
 * 1. 删除按钮平时不显示，鼠标移到那一行才出现，免得一排叉号把界面弄脏。
 * 2. 删除要**点两次**：第一次变成「确认」，3 秒内再点一次才真删。
 *    阅读进度是慢慢攒出来的，误点一次就没了会很烦。
 *
 * 配色全部从 UIManager 取，跟着 IDE 主题走，不硬编码颜色。
 */
public class ShelfPopup extends JPopupMenu {

    /** 书名超过这个长度就截断 */
    private static final int MAX_TITLE_CHARS = 22;
    /** 超过这个条数就出滚动条 */
    private static final int MAX_ROWS_BEFORE_SCROLL = 8;
    private static final int ROW_HEIGHT = 46;
    /** 删除相关的警示色。深浅主题下都够显眼，所以不跟着主题走 */
    private static final Color DANGER = new Color(0xE8, 0x5D, 0x5D);

    private final String currentBookId;
    private final Consumer<ReadingProgress> onOpen;
    private final Runnable onChanged;
    private final BookStore store;

    /** 装所有行的容器，删除后原地移除即可，不用重建整个弹窗 */
    private final JPanel listPanel = new JPanel();
    private JLabel countLabel;

    /**
     * 书架数据的读写口子。
     *
     * 为什么要抽这层：ShelfPopup 原本直接调 NovelSettingsState.getInstance()，
     * 而它背后的 ApplicationManager 在 IDE 之外是 null，
     * 一调就 NPE，等于这块界面没法离线渲染验证。抽出来后能塞一个内存实现进去。
     */
    public interface BookStore {
        List<ReadingProgress> recent();

        boolean remove(String bookId);

        int clear();
    }

    /** 真实实现：走持久化设置 */
    private static BookStore settingsStore() {
        return new BookStore() {
            @Override
            public List<ReadingProgress> recent() {
                NovelSettingsState s = NovelSettingsState.getInstance();
                return s == null ? new ArrayList<>() : s.recentBooks();
            }

            @Override
            public boolean remove(String bookId) {
                NovelSettingsState s = NovelSettingsState.getInstance();
                return s != null && s.removeProgress(bookId);
            }

            @Override
            public int clear() {
                NovelSettingsState s = NovelSettingsState.getInstance();
                return s == null ? 0 : s.clearProgress();
            }
        };
    }

    public ShelfPopup(String currentBookId,
                      Consumer<ReadingProgress> onOpen,
                      Runnable onChanged) {
        this(currentBookId, onOpen, onChanged, settingsStore());
    }

    public ShelfPopup(String currentBookId,
                      Consumer<ReadingProgress> onOpen,
                      Runnable onChanged,
                      BookStore store) {
        this.currentBookId = currentBookId;
        this.onOpen = onOpen;
        this.onChanged = onChanged;
        this.store = store;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);

        add(listPanel, BorderLayout.CENTER);
    }

    /**
     * 主题变了就重新取一遍色。
     *
     * 颜色是在构造时算好存进组件的，IDE 里用户切深浅主题时不会自己刷新，
     * 会出现「背景还是旧的、字已经变新色」的错配。重写这个方法补上。
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (listPanel != null) {
            listPanel.setOpaque(false);
        }
        refreshColors(this);
    }

    private void refreshColors(java.awt.Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof ShelfRow) {
                ((ShelfRow) child).applyTheme();
            } else if (child instanceof java.awt.Container) {
                refreshColors((java.awt.Container) child);
            }
        }
    }

    /** 每次弹出前调一次，按最新的记录重新填充 */
    public void reload() {
        removeAll();
        listPanel.removeAll();
        countLabel = null;

        List<ReadingProgress> books = store.recent();
        if (books.isEmpty()) {
            add(emptyView(), BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        for (ReadingProgress p : books) {
            listPanel.add(new ShelfRow(p, p.bookId != null && p.bookId.equals(currentBookId)));
        }

        if (books.size() > MAX_ROWS_BEFORE_SCROLL) {
            JScrollPane sp = new JScrollPane(listPanel);
            sp.setBorder(null);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.setPreferredSize(new Dimension(320, MAX_ROWS_BEFORE_SCROLL * ROW_HEIGHT));
            sp.getVerticalScrollBar().setUnitIncrement(16);
            add(sp, BorderLayout.CENTER);
        } else {
            add(listPanel, BorderLayout.CENTER);
        }
        add(header(), BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    private JPanel emptyView() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        JLabel l = new JLabel("还没有阅读记录");
        l.setForeground(secondaryColor());
        p.add(l, BorderLayout.CENTER);
        return p;
    }

    /** 底部：条数 + 清空 */
    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(6, 12, 2, 8));

        countLabel = new JLabel();
        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN,
                countLabel.getFont().getSize() - 1f));
        countLabel.setForeground(secondaryColor());
        p.add(countLabel, BorderLayout.WEST);

        JButton clear = new LinkButton("清空");
        clear.setToolTipText("清空全部阅读记录");
        clear.addActionListener(e -> {
            store.clear();
            onChanged.run();
            reload();
            if (store.recent().isEmpty()) {
                setVisible(false);
            }
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(clear);
        p.add(right, BorderLayout.EAST);

        updateCount();
        return p;
    }

    /** 删掉一行后更新计数；空了就提示 */
    private void updateCount() {
        if (countLabel == null) {
            return;
        }
        int n = listPanel.getComponentCount();
        if (n == 0) {
            countLabel.setText("已无阅读记录");
        } else {
            countLabel.setText(n + " 本");
        }
    }

    private void removeRow(JComponent row) {
        listPanel.remove(row);
        updateCount();
        onChanged.run();
        // 一行都没有就整体换成空态
        if (listPanel.getComponentCount() == 0) {
            reload();
            if (store.recent().isEmpty()) {
                setVisible(false);
            }
            return;
        }
        listPanel.revalidate();
        listPanel.repaint();
        // 行数变化会改变弹窗尺寸，重新打包一次
        setPreferredSize(null);
        pack();
    }

    // ---------------- 主题取色 ----------------

    static Color bg() {
        Color c = javax.swing.UIManager.getColor("PopupMenu.background");
        if (c != null) {
            return c;
        }
        Color panel = javax.swing.UIManager.getColor("Panel.background");
        return panel == null ? new Color(0x3C, 0x3F, 0x41) : panel;
    }

    static Color fg() {
        Color c = javax.swing.UIManager.getColor("Label.foreground");
        return c == null ? new Color(0xBB, 0xBB, 0xBB) : c;
    }

    static Color secondaryColor() {
        Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
        if (c != null) {
            return c;
        }
        Color f = fg();
        return new Color(f.getRed(), f.getGreen(), f.getBlue(), 150);
    }

    /**
     * 悬停时叠在行上的高亮色。
     *
     * 不要用 List.selectionBackground：深色主题下它是个饱和的蓝，
     * 铺上去以后浅灰的文字直接糊成一片，看不清。
     * 这里只取一点点前景色叠在背景上，深浅主题都成立。
     */
    static Color hoverColor() {
        Color base = bg();
        Color f = fg();
        boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) < 384;
        // 深色主题往亮里走、浅色主题往暗里走，各自 12%
        double ratio = 0.12;
        int r = mix(base.getRed(), f.getRed(), ratio);
        int g = mix(base.getGreen(), f.getGreen(), ratio);
        int b = mix(base.getBlue(), f.getBlue(), ratio);
        // 纯灰前景色可能让对比不足，兜一个下限
        if (Math.abs(r - base.getRed()) < 4 && Math.abs(g - base.getGreen()) < 4) {
            int d = dark ? 20 : -14;
            r = clamp(base.getRed() + d);
            g = clamp(base.getGreen() + d);
            b = clamp(base.getBlue() + d);
        }
        return new Color(r, g, b);
    }

    private static int mix(int a, int b, double ratio) {
        return clamp((int) Math.round(a + (b - a) * ratio));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ---------------- 纯函数，方便脱离 IDE 测试 ----------------

    /** 把时间戳说成「3 分钟前」，比绝对时间好读 */
    static String relativeTime(long updatedAt, long now) {
        if (updatedAt <= 0) {
            return "";
        }
        long diff = now - updatedAt;
        if (diff < 0) {
            return "刚刚";
        }
        long min = diff / 60000;
        if (min < 1) {
            return "刚刚";
        }
        if (min < 60) {
            return min + " 分钟前";
        }
        long hour = min / 60;
        if (hour < 24) {
            return hour + " 小时前";
        }
        long day = hour / 24;
        if (day < 30) {
            return day + " 天前";
        }
        long month = day / 30;
        if (month < 12) {
            return month + " 个月前";
        }
        return (month / 12) + " 年前";
    }

    /** 书名精简：去掉路径、超长截断 */
    static String shortTitle(String title, String location) {
        String name = (title == null || title.isEmpty()) ? location : title;
        if (name == null || name.isEmpty()) {
            return "（未知）";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        if (name.length() > MAX_TITLE_CHARS) {
            return name.substring(0, MAX_TITLE_CHARS) + "…";
        }
        return name;
    }

    /** 副标题：第 x 章 · 本地/网络 · 多久前 */
    static String subtitle(ReadingProgress p, long now) {
        return "第 " + (p.chapterIndex + 1) + " 章 · "
                + (p.safeType() == SourceType.LOCAL ? "本地" : "网络")
                + " · " + relativeTime(p.updatedAt, now);
    }

    // ---------------- 行 ----------------

    private final class ShelfRow extends JPanel {

        private final ReadingProgress progress;
        private final DeleteButton deleteButton;
        /** 提升成字段，主题切换时要重新上色 */
        private final JLabel nameLabel;
        private final JLabel subLabel;
        private final JLabel badgeLabel;
        private boolean hot;

        ShelfRow(ReadingProgress p, boolean isCurrent) {
            this.progress = p;
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            setBackground(bg());
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 6));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);

            JLabel name = new JLabel(shortTitle(p.title, p.location));
            name.setFont(name.getFont().deriveFont(Font.PLAIN));
            name.setForeground(fg());
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (p.location != null) {
                name.setToolTipText(p.location);
            }
            nameLabel = name;

            JLabel sub = new JLabel(subtitle(p, System.currentTimeMillis()));
            sub.setFont(sub.getFont().deriveFont(Font.PLAIN, sub.getFont().getSize() - 1.5f));
            sub.setForeground(secondaryColor());
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            subLabel = sub;

            text.add(name);
            text.add(Box.createVerticalStrut(1));
            text.add(sub);

            JLabel badge = null;
            if (isCurrent) {
                badge = new JLabel("当前");
                badge.setFont(badge.getFont().deriveFont(Font.PLAIN, badge.getFont().getSize() - 2f));
                badge.setForeground(secondaryColor());
                badge.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(secondaryColor()),
                        BorderFactory.createEmptyBorder(0, 4, 0, 4)));
            }
            badgeLabel = badge;

            deleteButton = new DeleteButton();
            deleteButton.setVisible(false);
            deleteButton.addActionListener(e -> onDeleteClicked());

            add(text, BorderLayout.CENTER);

            // 徽标和删除按钮排在同一个东侧容器里。
            // 之前是先 add(badge, EAST)、再 add(delete, EAST)，
            // BorderLayout 一个方位只放得下一个组件，第二次 add 会把徽标顶掉，
            // 结果「当前」从来没显示过。
            JPanel east = new JPanel();
            east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
            east.setOpaque(false);
            if (badge != null) {
                east.add(badge);
                east.add(Box.createHorizontalStrut(6));
            }
            east.add(deleteButton);
            add(east, BorderLayout.EAST);

            wire(name);
            wire(sub);
            wire(text);
            wire(this);
        }

        /** 统一挂三个监听：点击打开、进入/离开控制删除按钮的显隐 */
        private void wire(JComponent c) {
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            c.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    open();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setHot(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!stillInside(e)) {
                        setHot(false);
                    }
                }
            });
        }

        /**
         * 鼠标是不是真的离开了整行。
         *
         * event.getPoint() 是**事件源组件**的坐标系，不是这一行的，
         * 拿它直接给 contains() 用会判错（在标题和副标题之间移动就会把按钮收起来）。
         * 必须先换算成这一行的坐标。
         */
        private boolean stillInside(MouseEvent e) {
            java.awt.Point p = javax.swing.SwingUtilities.convertPoint(
                    e.getComponent(), e.getPoint(), ShelfRow.this);
            return ShelfRow.this.contains(p);
        }

        private void setHot(boolean hot) {
            this.hot = hot;
            applyTheme();
            deleteButton.setVisible(hot);
            if (!hot) {
                deleteButton.setArmed(false);
            }
            repaint();
        }

        /** 把当前主题色重新铺一遍；hot 状态要保住，别一刷新就掉 */
        void applyTheme() {
            setBackground(hot ? hoverColor() : bg());
            nameLabel.setForeground(fg());
            subLabel.setForeground(secondaryColor());
            if (badgeLabel != null) {
                badgeLabel.setForeground(secondaryColor());
                badgeLabel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(secondaryColor()),
                        BorderFactory.createEmptyBorder(0, 4, 0, 4)));
            }
            deleteButton.applyTheme();
            repaint();
        }

        private void open() {
            setVisible(false);
            ShelfPopup.this.setVisible(false);
            onOpen.accept(progress);
        }

        /** 第一次点变「确认」，第二次才真删 */
        private void onDeleteClicked() {
            if (!deleteButton.armed) {
                deleteButton.setArmed(true);
                return;
            }
            NovelSettingsState s = NovelSettingsState.getInstance();
            if (s != null) {
                s.removeProgress(progress.bookId);
            }
            removeRow(this);
        }
    }

    // ---------------- 两个自绘按钮 ----------------

    /** 文字链接样式的按钮，用来做「清空」 */
    private static final class LinkButton extends JButton {
        LinkButton(String text) {
            super(text);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusable(false);
            setForeground(secondaryColor());
            setFont(getFont().deriveFont(Font.PLAIN, getFont().getSize() - 1f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new java.awt.Insets(0, 4, 0, 4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            setForeground(getModel().isRollover() ? fg() : secondaryColor());
            super.paintComponent(g);
        }
    }

    /**
     * 删除按钮：平时是一个小叉，点一次变成「确认」，再点才真删。
     * 3 秒没动作自动退回小叉，避免一直停在确认态。
     */
    private static final class DeleteButton extends JButton {

        private boolean armed;
        private final Timer resetTimer = new Timer(3000, e -> setArmed(false));

        DeleteButton() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new java.awt.Insets(0, 5, 0, 5));
            setIcon(new CrossIcon());
            setToolTipText("删除这条阅读记录");
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (armed) {
                // 红底白字：确认态要一眼看出来，别让人以为还没点到。
                // 文字色必须显式设白，否则会继承下面那行的 DANGER，红底红字看不见。
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(DANGER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                g2.dispose();
                setForeground(Color.WHITE);
            } else {
                setForeground(getModel().isRollover() ? DANGER : secondaryColor());
            }
            super.paintComponent(g);
        }

        void setArmed(boolean armed) {
            this.armed = armed;
            resetTimer.stop();
            if (armed) {
                setIcon(null);
                setText("确认");
                setForeground(Color.WHITE);
                setFont(getFont().deriveFont(Font.PLAIN, getFont().getSize() - 1f));
                setToolTipText("再点一次即删除");
                resetTimer.restart();
            } else {
                setText(null);
                setIcon(new CrossIcon());
                setForeground(secondaryColor());
                setToolTipText("删除这条阅读记录");
            }
            revalidate();
            repaint();
        }

        /** 主题变了重新上色，armed 状态保持不变 */
        void applyTheme() {
            if (!armed) {
                setForeground(secondaryColor());
            }
            repaint();
        }
    }

    /** 一个小叉，跟着按钮前景色画 */
    private static final class CrossIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(c == null ? fg() : c.getForeground());
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int s = 3;
            int e = 10;
            g2.drawLine(x + s, y + s, x + e, y + e);
            g2.drawLine(x + e, y + s, x + s, y + e);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 13;
        }

        @Override
        public int getIconHeight() {
            return 13;
        }
    }
}
