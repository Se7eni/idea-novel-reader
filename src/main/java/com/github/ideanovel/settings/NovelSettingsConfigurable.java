package com.github.ideanovel.settings;

import com.github.ideanovel.service.NovelReaderService;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Settings > Tools > 摸鱼小说。
 * 用原生 Swing 手写布局，不依赖 IntelliJ 的 UI DSL，省去版本兼容问题。
 */
public class NovelSettingsConfigurable implements SearchableConfigurable {

    /** 设置项 id，与 plugin.xml 里的 configurable id 保持一致 */
    public static final String ID = "com.github.ideanovel.settings";

    private JPanel root;

    private JSpinner fontSize;
    private JSpinner lineSpacing;
    private JTextField fontFamily;
    private JComboBox<NovelSettingsState.ThemeMode> theme;

    private JCheckBox useRegexChapter;
    private JTextField chapterRegex;
    private JSpinner chunkSize;

    private JCheckBox autoScroll;
    private JSpinner autoScrollSpeed;
    private JSpinner paragraphIndent;
    private JCheckBox showChapterList;
    private JCheckBox compactToolbar;

    private JComboBox<NovelSettingsState.DisguiseMode> disguiseMode;
    private JCheckBox bossKeyHideWindow;
    private JCheckBox rememberProgress;
    private JSpinner remoteChapterLimit;

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "摸鱼小说";
    }

    @Override
    public @Nullable String getHelpTopic() {
        return null;
    }

    @Override
    public @Nullable JComponent createComponent() {
        NovelSettingsState s = NovelSettingsState.getInstance();

        fontSize = spinner(s == null ? 16 : s.fontSize, 9, 48, 1);
        lineSpacing = spinner(s == null ? 180 : s.lineSpacingPercent, 100, 400, 10);
        fontFamily = new JTextField(s == null ? "" : s.fontFamily, 16);
        fontFamily.setToolTipText("留空使用系统默认字体，例如填：微软雅黑 / 思源宋体 / Microsoft YaHei UI");
        theme = new JComboBox<>(NovelSettingsState.ThemeMode.values());
        theme.setSelectedItem(s == null ? NovelSettingsState.ThemeMode.FOLLOW_IDE : s.theme());

        useRegexChapter = new JCheckBox("用正则识别章节标题",
                s == null || s.useRegexChapter);
        chapterRegex = new JTextField(s == null ? "" : s.chapterRegex, 24);
        chapterRegex.setToolTipText("识别不出章节时会自动按字数分块");
        chunkSize = spinner(s == null ? 4000 : s.chunkSize, 500, 50000, 500);

        autoScroll = new JCheckBox("打开自动滚动翻页", s != null && s.autoScroll);
        autoScrollSpeed = spinner(s == null ? 2 : s.autoScrollSpeed, 1, 30, 1);
        paragraphIndent = spinner(s == null ? 2 : s.paragraphIndent, 0, 8, 1);
        showChapterList = new JCheckBox("显示章节目录", s == null || s.showChapterList);
        compactToolbar = new JCheckBox("工具栏用图标按钮（窄侧边栏更省地方）",
                s == null || s.compactToolbar);

        disguiseMode = new JComboBox<>(NovelSettingsState.DisguiseMode.values());
        disguiseMode.setSelectedItem(s == null ? NovelSettingsState.DisguiseMode.BUILD_LOG : s.disguise());
        bossKeyHideWindow = new JCheckBox("老板键直接隐藏整个工具窗（不做伪装）",
                s != null && s.bossKeyHideWindow);
        rememberProgress = new JCheckBox("记住阅读进度", s == null || s.rememberProgress);
        remoteChapterLimit = spinner(s == null ? 50 : s.remoteChapterLimit, 0, 2000, 10);

        root = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;
        row = addSectionTitle("阅读外观", row, c);
        row = addRow("正文字号", fontSize, row, c);
        row = addRow("行距（%）", lineSpacing, row, c);
        row = addRow("字体名称", fontFamily, row, c);
        row = addRow("配色主题", theme, row, c);
        row = addRow("首行缩进（字符）", paragraphIndent, row, c);
        row = addRow("显示章节目录", showChapterList, row, c);
        row = addRow("", compactToolbar, row, c);

        row = addSectionTitle("章节切分", row, c);
        row = addRow("", useRegexChapter, row, c);
        row = addRow("章节正则", chapterRegex, row, c);
        row = addRow("分块大小（字）", chunkSize, row, c);

        row = addSectionTitle("自动滚动", row, c);
        row = addRow("", autoScroll, row, c);
        row = addRow("速度（行/秒）", autoScrollSpeed, row, c);

        row = addSectionTitle("隐身与进度", row, c);
        row = addRow("伪装成", disguiseMode, row, c);
        row = addRow("", bossKeyHideWindow, row, c);
        row = addRow("", rememberProgress, row, c);
        row = addRow("网络小说解析章节上限", remoteChapterLimit, row, c);

        // 把剩余空间顶上去
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.gridy = row;
        root.add(new JPanel(), c);

        return root;
    }

    private JSpinner spinner(int value, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, step));
    }

    private int addSectionTitle(String text, int row, GridBagConstraints c) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        JLabel label = new JLabel("<html><b>" + text + "</b></html>");
        root.add(label, c);
        c.gridwidth = 1;
        return row + 1;
    }

    private int addRow(String labelText, JComponent field, int row, GridBagConstraints c) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        if (labelText != null && !labelText.isEmpty()) {
            root.add(new JLabel(labelText), c);
        }
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(field, c);
        return row + 1;
    }

    @Override
    public boolean isModified() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null || fontSize == null) {
            return false;
        }
        return intOf(fontSize) != s.fontSize
                || intOf(lineSpacing) != s.lineSpacingPercent
                || !nullSafe(fontFamily.getText()).trim().equals(nullSafe(s.fontFamily))
                || theme.getSelectedItem() != s.theme()
                || useRegexChapter.isSelected() != s.useRegexChapter
                || !nullSafe(chapterRegex.getText()).equals(nullSafe(s.chapterRegex))
                || intOf(chunkSize) != s.chunkSize
                || autoScroll.isSelected() != s.autoScroll
                || intOf(autoScrollSpeed) != s.autoScrollSpeed
                || intOf(paragraphIndent) != s.paragraphIndent
                || showChapterList.isSelected() != s.showChapterList
                || compactToolbar.isSelected() != s.compactToolbar
                || disguiseMode.getSelectedItem() != s.disguise()
                || bossKeyHideWindow.isSelected() != s.bossKeyHideWindow
                || rememberProgress.isSelected() != s.rememberProgress
                || intOf(remoteChapterLimit) != s.remoteChapterLimit;
    }

    @Override
    public void apply() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null) {
            return;
        }
        boolean regexChanged = !nullSafe(chapterRegex.getText()).equals(nullSafe(s.chapterRegex))
                || useRegexChapter.isSelected() != s.useRegexChapter
                || intOf(chunkSize) != s.chunkSize;

        s.fontSize = intOf(fontSize);
        s.lineSpacingPercent = intOf(lineSpacing);
        s.fontFamily = nullSafe(fontFamily.getText()).trim();
        s.themeMode = theme.getSelectedItem() == null ? 0 : ((NovelSettingsState.ThemeMode) theme.getSelectedItem()).ordinal();
        s.useRegexChapter = useRegexChapter.isSelected();
        s.chapterRegex = nullSafe(chapterRegex.getText());
        s.chunkSize = intOf(chunkSize);
        s.autoScroll = autoScroll.isSelected();
        s.autoScrollSpeed = intOf(autoScrollSpeed);
        s.paragraphIndent = intOf(paragraphIndent);
        s.showChapterList = showChapterList.isSelected();
        s.compactToolbar = compactToolbar.isSelected();
        s.disguiseMode = disguiseMode.getSelectedItem() == null ? 0
                : ((NovelSettingsState.DisguiseMode) disguiseMode.getSelectedItem()).ordinal();
        s.bossKeyHideWindow = bossKeyHideWindow.isSelected();
        s.rememberProgress = rememberProgress.isSelected();
        s.remoteChapterLimit = intOf(remoteChapterLimit);

        NovelReaderService service = NovelReaderService.getInstance();
        if (regexChanged) {
            service.reparse();
        }
        service.fireSettingsChanged();
    }

    @Override
    public void reset() {
        NovelSettingsState s = NovelSettingsState.getInstance();
        if (s == null || fontSize == null) {
            return;
        }
        fontSize.setValue(s.fontSize);
        lineSpacing.setValue(s.lineSpacingPercent);
        fontFamily.setText(nullSafe(s.fontFamily));
        theme.setSelectedItem(s.theme());
        useRegexChapter.setSelected(s.useRegexChapter);
        chapterRegex.setText(nullSafe(s.chapterRegex));
        chunkSize.setValue(s.chunkSize);
        autoScroll.setSelected(s.autoScroll);
        autoScrollSpeed.setValue(s.autoScrollSpeed);
        paragraphIndent.setValue(s.paragraphIndent);
        showChapterList.setSelected(s.showChapterList);
        compactToolbar.setSelected(s.compactToolbar);
        disguiseMode.setSelectedItem(s.disguise());
        bossKeyHideWindow.setSelected(s.bossKeyHideWindow);
        rememberProgress.setSelected(s.rememberProgress);
        remoteChapterLimit.setValue(s.remoteChapterLimit);
    }

    private static int intOf(JSpinner spinner) {
        Object v = spinner.getValue();
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
