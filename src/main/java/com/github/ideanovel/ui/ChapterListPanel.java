package com.github.ideanovel.ui;

import com.github.ideanovel.model.Chapter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 章节目录：带搜索框，双击跳章。
 */
public class ChapterListPanel extends JPanel {

    private final DefaultListModel<Chapter> model = new DefaultListModel<>();
    private final JBList<Chapter> list = new JBList<>(model);
    private final JBTextField searchField = new JBTextField();
    private List<Chapter> allChapters = new ArrayList<>();
    /** 当前真正显示在列表里的章节（refilter 后同步），避免和 model 两处维护 */
    private List<Chapter> visibleChapters = new ArrayList<>();
    private ChapterSelectListener listener;
    private FilterListener filterListener;

    public interface ChapterSelectListener {
        void onChapterSelected(int index);
    }

    /** 搜索条件变化时回调，传空串表示没有过滤 */
    public interface FilterListener {
        void onFilterChanged(String summary);
    }

    public ChapterListPanel() {
        setLayout(new BorderLayout(0, 4));

        searchField.getEmptyText().setText("搜索章节标题");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(list.getFont().deriveFont(Font.PLAIN));
        // 固定行高：JList 不必再为每个单元格调 getPreferredSize 来推总高度，
        // 上万章时这是滚动流畅与否的关键。行高按当前字体算，换字体也不会串行。
        list.setFixedCellHeight(list.getFontMetrics(list.getFont()).getHeight() + 2);
        list.getEmptyText().setText("暂无章节");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    fireSelect();
                }
            }
        });

        // 目录通常只有几百像素宽，长章节标题必然被截断，给个完整提示
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object value,
                                                                  int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                setText(value == null ? "" : value.toString());
                setToolTipText(value == null ? null : value.toString());
                return this;
            }
        });

        add(searchField, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
    }

    public void setSelectListener(ChapterSelectListener listener) {
        this.listener = listener;
    }

    public void setFilterListener(FilterListener filterListener) {
        this.filterListener = filterListener;
    }

    /**
     * 载入全部章节。
     *
     * 注意：**这里不能做数量截断**。列表现在按可视区惰性渲染，几万条也只是滚动条长一点，
     * 不需要为了性能砍掉尾部章节——砍掉的话用户就永远点不到后面那些章。
     */
    public void setChapters(List<Chapter> chapters) {
        this.allChapters = chapters == null ? new ArrayList<>() : new ArrayList<>(chapters);
        refilter();
    }

    /** 只用来显示统计信息，比如「匹配 12 / 1089 章」 */
    public int getTotalCount() {
        return allChapters.size();
    }

    /** 选中指定章节，并保证它在可视范围内 */
    public void selectChapter(int index) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).getIndex() == index) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private void refilter() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        visibleChapters = new ArrayList<>();
        for (Chapter c : allChapters) {
            if (keyword.isEmpty() || c.getTitle().toLowerCase().contains(keyword)) {
                visibleChapters.add(c);
            }
        }

        // 一次性换掉整个列表内容。配合 setFixedCellHeight，JList 不会为每条都去调
        // 渲染器算高度，上千章也只是滚动条长一点。真正的渲染本来就是惰性的，
        // 只会画可视区那几十条。
        model.clear();
        if (!visibleChapters.isEmpty()) {
            model.addAll(visibleChapters);
        }

        if (model.isEmpty()) {
            list.getEmptyText().setText(keyword.isEmpty() ? "暂无章节" : "没有匹配的章节");
        }

        if (filterListener != null) {
            String kw = searchField.getText() == null ? "" : searchField.getText().trim();
            filterListener.onFilterChanged(kw.isEmpty() ? "" : filterSummary());
        }
    }

    /** 供外部（状态栏）显示过滤结果，比如搜索时提示匹配了多少章 */
    public String filterSummary() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        if (keyword.isEmpty()) {
            return "";
        }
        return "匹配 " + visibleChapters.size() + " / " + allChapters.size() + " 章";
    }

    private void fireSelect() {
        Chapter selected = list.getSelectedValue();
        if (selected != null && listener != null) {
            final int index = selected.getIndex();
            SwingUtilities.invokeLater(() -> listener.onChapterSelected(index));
        }
    }
}
