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
        // 短行合并：小说常有「一句话换一行」的排版，逐行切会让弹窗一行只剩几个字，
        // 翻得又碎又快。这里把连续的短行攒起来，攒够 maxChars 或遇到空行（自然段边界）
        // 再落盘，尽量让每次显示接近一行。
        StringBuilder pending = new StringBuilder();
        for (String paragraph : text.split("\n")) {
            String trimmed = stripIndent(paragraph);
            if (trimmed.isEmpty()) {
                flush(pending, out, maxChars);
                continue;
            }
            pending.append(trimmed);
            if (pending.length() >= maxChars) {
                flush(pending, out, maxChars);
            }
        }
        flush(pending, out, maxChars);
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** 把攒着的文本交给 {@link #splitParagraph} 切，然后清空缓冲 */
    private static void flush(StringBuilder pending, List<String> out, int maxChars) {
        if (pending.length() == 0) {
            return;
        }
        splitParagraph(pending.toString(), maxChars, out);
        pending.setLength(0);
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
            // 断点往往正好落在「。」和「”」之间：只断在句号后的话，收尾引号会被
            // 甩到下一段，弹窗就出现「。”」这种只有一两个标点的碎片。
            // 这里把紧跟断点的空白和收尾引号一起吃掉。
            breakAt = extendOverClosers(paragraph, breakAt);
            addSegment(paragraph.substring(start, breakAt), out);
            start = breakAt;
        }
        if (len - start > 0) {
            addSegment(paragraph.substring(start), out);
        } else if (out.isEmpty()) {
            addSegment(paragraph, out);
        }
    }

    /** 从 pos 起吃掉紧邻的空白与收尾引号，返回新的断点 */
    private static int extendOverClosers(String s, int pos) {
        int i = pos;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || CLOSERS.indexOf(c) >= 0) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * 把一段加进结果。纯标点（含空白）的段并回前一段——
     * 这类碎片在弹窗里就是一行只剩下「。”」，翻过去毫无意义。
     */
    private static void addSegment(String segment, List<String> out) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        if (isOnlyPunctuation(segment) && !out.isEmpty()) {
            out.set(out.size() - 1, out.get(out.size() - 1) + segment);
            return;
        }
        out.add(segment);
    }

    /** 是否只由标点和空白组成（没有任何文字或数字） */
    private static boolean isOnlyPunctuation(String s) {
        if (s.trim().isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
            if (SENTENCE_ENDS.indexOf(c) < 0 && CLOSERS.indexOf(c) < 0
                    && c != ' ' && c != '\t' && c != '\n') {
                return false;
            }
        }
        return true;
    }
}
