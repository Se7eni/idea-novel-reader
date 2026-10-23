package com.github.ideanovel.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节正文 → 提示框小段。
 *
 * <p>提示框一次只显示一小截文字（截图里那种「一兩句 + (n/N)」的形态），
 * 太长会糊满半个屏幕，太短翻到手抽筋。切法：
 * 先按空行/换行拆成自然段，每段再按<b>句末标点</b>切成不超过 {@link #MAX_CHARS} 的小段；
 * 整段连标点都没有的超长句才允许硬切，绝不把句子腰斩在有标点可用的时候。
 *
 * <p>纯函数、不碰 Swing 和平台类，探针可以直接对边界情况做断言。
 */
public final class ChapterChunks {

    /** 单段最大字符数。30 字在 13 号字下正好一行，弹出框一次显示一句，不折行 */
    public static final int MAX_CHARS = 30;

    /** 句末标点：优先在这些字符后面断开 */
    private static final String SENTENCE_ENDS = "。！？；…!?;";

    /** 句末标点后面跟着的收尾引号，断点算在引号之后 */
    private static final String CLOSERS = "”’」』\")";

    private ChapterChunks() {
    }

    /** 按默认长度切段。返回值永不为 null；正文为空时返回只含一段空串的列表 */
    public static List<String> split(String body) {
        return split(body, MAX_CHARS);
    }

    static List<String> split(String body, int maxChars) {
        List<String> out = new ArrayList<>();
        String text = body == null ? "" : body;
        if (maxChars < 4) {
            maxChars = 4;
        }
        for (String paragraph : text.split("\n")) {
            String trimmed = stripIndent(paragraph);
            if (trimmed.isEmpty()) {
                continue;
            }
            splitParagraph(trimmed, maxChars, out);
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** 去掉段首的全角/半角缩进和段尾空白 */
    private static String stripIndent(String paragraph) {
        int i = 0;
        while (i < paragraph.length()) {
            char c = paragraph.charAt(i);
            if (c != '　' && c != ' ' && c != '\t' && c != '\r') {
                break;
            }
            i++;
        }
        int end = paragraph.length();
        while (end > i) {
            char c = paragraph.charAt(end - 1);
            if (c != '　' && c != ' ' && c != '\t' && c != '\r') {
                break;
            }
            end--;
        }
        return paragraph.substring(i, end);
    }

    /**
     * 一段切成若干小段。
     *
     * <p>断点选择：理想断点是第 maxChars 个字符，但如果往回半个段长以内
     * 有句末标点，就断在最后一个标点（含收尾引号）之后——
     * 读者看到的永远是一句完整的话，而不是被拦腰截断的半句。
     */
    private static void splitParagraph(String paragraph, int maxChars, List<String> out) {
        int start = 0;
        int len = paragraph.length();
        while (len - start > maxChars) {
            int ideal = start + maxChars;
            int breakAt = ideal;
            int searchFrom = start + maxChars / 2;
            for (int j = ideal; j > searchFrom; j--) {
                char c = paragraph.charAt(j - 1);
                if (SENTENCE_ENDS.indexOf(c) >= 0) {
                    breakAt = j;
                    break;
                }
                // 句末标点后跟收尾引号的，断在引号后面
                if (CLOSERS.indexOf(c) >= 0 && j - 2 >= searchFrom
                        && SENTENCE_ENDS.indexOf(paragraph.charAt(j - 2)) >= 0) {
                    breakAt = j;
                    break;
                }
            }
            out.add(paragraph.substring(start, breakAt));
            start = breakAt;
        }
        if (len - start > 0) {
            out.add(paragraph.substring(start));
        } else if (out.isEmpty()) {
            out.add(paragraph);
        }
    }
}
