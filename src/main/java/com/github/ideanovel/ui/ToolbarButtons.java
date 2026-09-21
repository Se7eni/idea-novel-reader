package com.github.ideanovel.ui;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * 工具栏按钮的统一样式。
 *
 * 之前工具栏里是「文字按钮 + 图标按钮」两种外观混着放：文字按钮有 JDK 默认的
 * 凸起边框和渐变底，图标按钮又是另一套，凑在一起显得乱。这里统一成扁平样式：
 *
 * - 平时无边框、透明底，只有悬停/按下时才浮出一层淡淡的底色
 * - 圆角 6px，和 IDE 自己的工具窗按钮风格一致
 * - 颜色全部取自 UIManager，深浅主题都跟着走
 * - 统一高度和最小宽度，文字按钮和图标按钮排在一起时基线一致
 */
final class ToolbarButtons {

    /** 工具栏按钮统一高度 */
    static final int HEIGHT = 26;
    /** 带文字的按钮最小宽度，免得单字按钮缩成一条 */
    private static final int MIN_TEXT_WIDTH = 52;
    /** 图标和文字之间留的空隙 */
    private static final int ICON_TEXT_GAP = 5;
    /** 按钮内容区左右各留的白 */
    private static final int H_PADDING = 9;

    private ToolbarButtons() {
    }

    // ---------------- 取色 ----------------

    static Color fg() {
        Color c = javax.swing.UIManager.getColor("Label.foreground");
        return c == null ? new Color(0xBB, 0xBB, 0xBB) : c;
    }

    /** 悬停底色：优先用 IDE 自己的悬停色，没有就自己算一层 */
    static Color hoverBg() {
        Color c = javax.swing.UIManager.getColor("ActionButton.hoverBackground");
        if (c != null) {
            return c;
        }
        return blend(baseBg(), fg(), 0.12f);
    }

    /** 按下底色，比悬停重一点 */
    static Color pressedBg() {
        Color c = javax.swing.UIManager.getColor("ActionButton.pressedBackground");
        if (c != null) {
            return c;
        }
        return blend(baseBg(), fg(), 0.20f);
    }

    private static Color baseBg() {
        Color c = javax.swing.UIManager.getColor("Panel.background");
        if (c != null) {
            return c;
        }
        Color popup = javax.swing.UIManager.getColor("PopupMenu.background");
        return popup == null ? new Color(0x3C, 0x3F, 0x41) : popup;
    }

    /** 把 over 按 ratio 混到 base 上 */
    private static Color blend(Color base, Color over, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        return new Color(
                Math.round(base.getRed() + (over.getRed() - base.getRed()) * r),
                Math.round(base.getGreen() + (over.getGreen() - base.getGreen()) * r),
                Math.round(base.getBlue() + (over.getBlue() - base.getBlue()) * r));
    }

    static Color disabledFg() {
        Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
        return c == null ? blend(fg(), baseBg(), 0.5f) : c;
    }

    // ---------------- 构造 ----------------

    /**
     * 文字按钮。
     *
     * 宽度按「文字 + 图标 + 间距 + 左右内边距」算。
     * 早先只算文字宽度，ReaderPanel 随后 setIcon 时图标把可用空间挤掉，
     * Metal 外观发现文字放不下就画成「…」，两个汉字的按钮全变成省略号。
     * 所以这里留足余量，且下面 setIcon 后不再重算。
     */
    static JButton text(String label, String tooltip, Runnable action) {
        JButton b = new FlatButton(label);
        b.setToolTipText(tooltip);
        b.setPreferredSize(new Dimension(widthFor(b, label, null), HEIGHT));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** 文案 + 可选图标的按钮，宽度一次算准 */
    static JButton textWithIcon(String label, String tooltip, Icon icon, Runnable action) {
        JButton b = new FlatButton(label);
        b.setToolTipText(tooltip);
        if (icon != null) {
            b.setIcon(icon);
            b.setIconTextGap(ICON_TEXT_GAP);
        }
        b.setPreferredSize(new Dimension(widthFor(b, label, icon), HEIGHT));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static int widthFor(JButton b, String label, Icon icon) {
        int text = b.getFontMetrics(b.getFont()).stringWidth(label == null ? "" : label);
        int iconW = icon == null ? 0 : icon.getIconWidth() + ICON_TEXT_GAP;
        return Math.max(MIN_TEXT_WIDTH, text + iconW + H_PADDING * 2);
    }

    /** 纯图标按钮 */
    static JButton icon(String tooltip, Icon icon, Runnable action) {
        JButton b = new FlatButton(null);
        b.setIcon(icon);
        b.setToolTipText(tooltip);
        b.setPreferredSize(new Dimension(32, HEIGHT));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** 窄竖线，把导航组和阅读控制组分开 */
    static JComponent separator() {
        javax.swing.JPanel p = new javax.swing.JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(blend(baseBg(), fg(), 0.25f));
                g2.setStroke(new BasicStroke(1f));
                int x = getWidth() / 2;
                g2.drawLine(x, 5, x, getHeight() - 5);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(9, HEIGHT));
        return p;
    }

    /**
     * 扁平按钮。
     *
     * 用「自己画底、不画边框」的方式来做，而不是改 Border：
     * JDK 的 Metal/Nimbus 外观会在 setBorder(null) 后仍然画自己的背景层，
     * 只有把 contentAreaFilled 关掉、在 paintComponent 里手动画，才能真正扁下去。
     */
    private static final class FlatButton extends JButton {

        FlatButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            setMargin(new Insets(2, 8, 2, 8));
            setFont(getFont().deriveFont(Font.PLAIN, getFont().getSize()));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(fg());
        }

        @Override
        public void updateUI() {
            super.updateUI();
            // 换主题后重新取一次色，否则切深浅主题按钮颜色不跟着变
            setForeground(fg());
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            ButtonModel m = getModel();
            Color fill = null;
            if (!isEnabled()) {
                fill = null;
            } else if (m.isPressed() && m.isArmed()) {
                fill = pressedBg();
            } else if (m.isRollover()) {
                fill = hoverBg();
            }

            if (fill != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
            }

            setForeground(isEnabled() ? fg() : disabledFg());
            super.paintComponent(g);
        }
    }
}
