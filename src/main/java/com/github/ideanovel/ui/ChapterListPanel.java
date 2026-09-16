package com.github.ideanovel.ui;

import com.github.ideanovel.model.Chapter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;

import javax.swing.DefaultListModel;
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

    private static final int MAX_VISIBLE = 800;

    private final DefaultListModel<Chapter> model = new DefaultListModel<>();
    private final JBList<Chapter> list = new JBList<>(model);
    private final JBTextField searchField = new JBTextField();
    private List<Chapter> allChapters = new ArrayList<>();
    private ChapterSelectListener listener;

    public interface ChapterSelectListener {
        void onChapterSelected(int index);
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
        list.getEmptyText().setText("暂无章节");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    fireSelect();
                }
            }
        });

        add(searchField, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
    }

    public void setSelectListener(ChapterSelectListener listener) {
        this.listener = listener;
    }

    public void setChapters(List<Chapter> chapters) {
        this.allChapters = chapters == null ? new ArrayList<>() : new ArrayList<>(chapters);
        refilter();
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
        model.clear();
        int count = 0;
        for (Chapter c : allChapters) {
            if (keyword.isEmpty() || c.getTitle().toLowerCase().contains(keyword)) {
                model.addElement(c);
                count++;
                if (count >= MAX_VISIBLE) {
                    break;
                }
            }
        }
        if (model.isEmpty()) {
            list.getEmptyText().setText(keyword.isEmpty() ? "暂无章节" : "没有匹配的章节");
        }
    }

    private void fireSelect() {
        Chapter selected = list.getSelectedValue();
        if (selected != null && listener != null) {
            final int index = selected.getIndex();
            SwingUtilities.invokeLater(() -> listener.onChapterSelected(index));
        }
    }
}
