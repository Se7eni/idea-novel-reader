package com.github.ideanovel.ui;

import com.github.ideanovel.service.NovelReaderService;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Font;
import java.util.List;

/**
 * 源码伪装面板：把小说正文渲染成一个 Java 文件的样子，正文藏在 Javadoc 注释块里。
 *
 * 和另外三种伪装的区别：那三种是「假装在干别的」，看不了小说；
 * 这种是「看起来在读代码，实际上还在读小说」——老板扫一眼是源码视图，
 * 而且侧边栏里出现源码视图很自然（不像构建日志那样反常）。
 *
 * 面板本身只负责「放进侧边栏、跟着进度滚」这件事；
 * 内容怎么拼、怎么上色统一交给 {@link CodeDocument}，
 * 因为代码弹出框（{@link CodePopupContent}）要用同一份内容，
 * 两处各写一遍迟早出现「同一章在两种阅读方式里长得不一样」。
 */
public class SourceDisguisePanel extends javax.swing.JPanel {

    private final JTextPane pane = new JTextPane();

    public SourceDisguisePanel() {
        setLayout(new BorderLayout());
        pane.setEditable(false);
        applyEditorFont();
        add(new JBScrollPane(pane), BorderLayout.CENTER);
    }

    /** 字体跟 IDE 编辑器一致。字体比配色更容易穿帮。 */
    private void applyEditorFont() {
        pane.setFont(new Font(CodeDocument.editorFontFamily(), Font.PLAIN,
                CodeDocument.editorFontSize()));
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

    /**
     * 章节 → 源码片段的拼装，实现已挪到 {@link CodeDocument}。
     *
     * 这里保留一个转发方法，是为了让脱离 IDE 的探针仍能对着「面板真正用的那个入口」
     * 做产出比对（改了拼装逻辑就跑 tools/probe/CodeGoldenProbe.java 校验），
     * 而不是对着一个只有测试才走的路径验证。
     */
    static List<CodeDocument.Line> buildLines(String title, String body, int index) {
        return CodeDocument.buildLines(title, body, index);
    }

    private void render(String title, String body, int index) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 空文档，忽略
        }

        // 侧边栏这份不带行号：工具栏本来就窄，行号纯占地方
        CodeDocument.writePlain(doc, buildLines(title, body, index));
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
}
