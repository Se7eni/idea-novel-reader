package com.github.ideanovel.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

/**
 * 工具栏用的手绘图标。
 *
 * 独立成一个类的原因有两个：
 * 1. 不引外部图片资源，纯 Graphics2D 画，颜色跟着 UIManager 的主题前景色走，深浅色主题都不会瞎；
 * 2. 图标代码不依赖 Project / ToolWindow 之类的平台对象，可以脱离 IDEA 单独渲染出来做视觉比对。
 */
public final class ReaderIcons {

    private ReaderIcons() {
    }

    /** 主题前景色；UIManager 拿不到时给个中性灰兜底 */
    public static Color iconColor() {
        Color c = javax.swing.UIManager.getColor("Label.foreground");
        return c == null ? new Color(0xBB, 0xBB, 0xBB) : c;
    }

    /** 上一章 / 下一章：左向、右向实心三角 */
    public static Icon arrowIcon(final boolean next) {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                Polygon p = next
                        ? new Polygon(new int[]{x + 4, x + 12, x + 4}, new int[]{y + 2, y + 8, y + 14}, 3)
                        : new Polygon(new int[]{x + 12, x + 4, x + 12}, new int[]{y + 2, y + 8, y + 14}, 3);
                g2.fillPolygon(p);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /**
     * 自动滚动（停止态）：向下的箭头 + 底部横线。
     *
     * 早先这里画的是一个右向实心三角，跟「下一章」的 ▶ 只差一个像素，实际用起来分不出来。
     * 现在换成向下方向并补一条底线：方向本身就和 ▶ 正交，加上底线的轮廓差异，缩小到 16px 也能一眼分清，
     * 语义上也贴合"内容向下滚动"。
     */
    public static Icon autoScrollIcon() {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // 箭头杆 + 两侧箭翼，尖端收在底线之上
                g2.drawLine(x + 8, y + 2, x + 8, y + 9);
                g2.drawLine(x + 5, y + 6, x + 8, y + 9);
                g2.drawLine(x + 11, y + 6, x + 8, y + 9);
                // 底部横线：滚到底会停在这条线上，也进一步把轮廓和实心三角区分开
                g2.drawLine(x + 4, y + 12, x + 12, y + 12);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** 自动滚动进行中：两根竖条（暂停），再点一下就是停止 */
    public static Icon pauseIcon() {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 6, y + 3, x + 6, y + 13);
                g2.drawLine(x + 10, y + 3, x + 10, y + 13);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** 字号图标：A- / A+ */
    public static Icon fontIcon(final boolean bigger) {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g2.drawString("A", x + 1, y + 12);
                g2.setStroke(new BasicStroke(1.6f));
                int cx = x + 11;
                int cy = y + 8;
                g2.drawLine(cx - 3, cy, cx + 3, cy);
                if (bigger) {
                    g2.drawLine(cx, cy - 3, cx, cy + 3);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** 设置：一个小齿轮（中心孔 + 八根辐条） */
    public static Icon gearIcon() {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                int cx = x + 8;
                int cy = y + 8;
                g2.drawOval(cx - 4, cy - 4, 8, 8);
                g2.drawOval(cx - 1, cy - 1, 2, 2);
                g2.setStroke(new BasicStroke(1.6f));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    int x1 = cx + (int) Math.round(Math.cos(a) * 4);
                    int y1 = cy + (int) Math.round(Math.sin(a) * 4);
                    int x2 = cx + (int) Math.round(Math.cos(a) * 6.5);
                    int y2 = cy + (int) Math.round(Math.sin(a) * 6.5);
                    g2.drawLine(x1, y1, x2, y2);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** 隐身（老板键）：一副墨镜，一眼就知道是"遮起来" */
    public static Icon maskIcon() {
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(g);
                g2.setColor(iconColor());
                g2.setStroke(new BasicStroke(1.4f));
                // 左右镜片
                g2.drawRoundRect(x + 1, y + 6, 6, 5, 3, 3);
                g2.drawRoundRect(x + 9, y + 6, 6, 5, 3, 3);
                // 鼻梁
                g2.drawLine(x + 7, y + 7, x + 9, y + 7);
                // 镜腿
                g2.drawLine(x + 1, y + 7, x, y + 5);
                g2.drawLine(x + 15, y + 7, x + 16, y + 5);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    private static Graphics2D prepare(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }
}
