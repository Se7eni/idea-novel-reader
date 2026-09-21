package com.github.ideanovel.parser;

import com.github.ideanovel.model.Chapter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节切分器：优先按正则识别章节标题，识别不出来就按固定长度分块兜底，
 * 保证任何 txt 都能读，而不是只认标准格式的小说。
 */
public final class ChapterParser {

    /** 默认章节正则：第X章 / 第X节 / 第X回 / 卷 / 篇 等常见写法 */
    public static final String DEFAULT_REGEX =
            "^\\s*(?:第\\s*[0-9零一二三四五六七八九十百千万两]+\\s*[章节回卷篇集折幕部]" +
            "|Chapter\\s+\\d+|CHAPTER\\s+\\d+)";

    private ChapterParser() {
    }

    /**
     * 切分章节。
     *
     * @param content    全文
     * @param useRegex   是否启用正则识别
     * @param regex      章节正则
     * @param chunkSize  正则失效时的分块大小
     */
    public static List<Chapter> parse(String content, boolean useRegex, String regex, int chunkSize) {
        List<Chapter> chapters = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chapters;
        }

        if (useRegex && regex != null && !regex.trim().isEmpty()) {
            chapters = parseByRegex(content, regex);
        }

        // 识别到的章节太少，说明这份文本不按套路出牌，退回按长度分块
        if (chapters.size() < 2) {
            chapters = parseByChunk(content, chunkSize <= 0 ? 4000 : chunkSize);
        }
        return chapters;
    }

    private static List<Chapter> parseByRegex(String content, String regex) {
        List<Chapter> chapters = new ArrayList<>();
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, Pattern.MULTILINE);
        } catch (Exception e) {
            return chapters;
        }

        Matcher matcher = pattern.matcher(content);
        List<int[]> hits = new ArrayList<>();
        while (matcher.find()) {
            hits.add(new int[]{matcher.start(), matcher.end()});
            // 章节多的时候没必要全扫，提前止损
            if (hits.size() > 20000) {
                break;
            }
        }
        if (hits.isEmpty()) {
            return chapters;
        }

        // 章节起止区间：{标题起始, 匹配结束, 正文结束}
        List<int[]> ranges = mergeDuplicateHeadings(content, hits);

        // 第一章标题之前若有内容，单独作为"开头"。
        // 用 hits.get(0)[0] 而不是 ranges 的第一项：后者已经是重复行里的最后一条了，
        // 用它会把多出来的那几个标题行算进「开头」的尾巴里。
        if (hits.get(0)[0] > 0) {
            String head = content.substring(0, hits.get(0)[0]).trim();
            if (head.length() > 10) {
                chapters.add(new Chapter(0, "开头", 0, hits.get(0)[0]));
            }
        }

        for (int[] r : ranges) {
            int start = r[0];
            int titleEnd = r[1];
            int end = r[2];
            if (end <= start) {
                continue;
            }
            chapters.add(new Chapter(chapters.size(), extractTitle(content, start, titleEnd), start, end));
        }
        return chapters;
    }

    /**
     * 把连续重复出现的标题行合成一个章节。
     *
     * 有些 txt（尤其从网站抓取或转换工具导出）会把同一行标题连着写两遍甚至三遍：
     *
     *     第84章 好舅舅
     *     第84章 好舅舅
     *     回到南医大男生宿舍时……
     *
     * 直接切会得到两个「第84章」，前一个只有标题没有正文。处理办法是把这些连续、
     * 中间没有正文的匹配归为一组，然后：
     *
     * - 章节起点取组内**最后**一条：渲染时标题只出现一次，后面紧跟正文
     * - 章节终点取**下一组的第 1 条**：这样下一章那几个冗余的重复行不会
     *   漏到本章末尾，而是落在谁都不显示的空隙里
     *
     * 判定「有没有正文」时用这一标题行的**行结尾**到下一个匹配起点之间的区间。
     * 行尾必须按匹配结束位置往后找，不能从 start 找 —— `^\s*` 会跨过前面的空行，
     * matcher.start() 常常落在标题行之前的那个空行上，从 start 找会定错行，
     * 于是「第84章 好舅舅」这一行被当成正文，重复就漏过去了。
     *
     * @return 每个元素为 {start, matchEnd, end}
     */
    private static List<int[]> mergeDuplicateHeadings(String content, List<int[]> hits) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < hits.size()) {
            int j = i;
            while (j + 1 < hits.size()
                    && !hasText(content, endOfLine(content, hits.get(j)[1]), hits.get(j + 1)[0])) {
                j++;
            }
            int[] last = hits.get(j);
            int next = (j + 1 < hits.size()) ? hits.get(j + 1)[0] : content.length();
            ranges.add(new int[]{last[0], last[1], next});
            i = j + 1;
        }
        return ranges;
    }

    /** 返回 pos 所在那一行的换行符之后的位置 */
    private static int endOfLine(String content, int pos) {
        int nl = content.indexOf('\n', Math.min(pos, Math.max(0, content.length() - 1)));
        return nl < 0 ? content.length() : nl + 1;
    }

    /** [from, to) 之间有没有非空白字符 */
    private static boolean hasText(String content, int from, int to) {
        int limit = Math.min(to, content.length());
        for (int i = Math.max(0, from); i < limit; i++) {
            char c = content.charAt(i);
            if (!Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /** 从标题行里截出干净的标题，只取这一行 */
    private static String extractTitle(String content, int start, int matchEnd) {
        int lineEnd = content.indexOf('\n', matchEnd > start ? matchEnd : start);
        if (lineEnd < 0) {
            lineEnd = Math.min(content.length(), start + 80);
        }
        String line = content.substring(start, lineEnd).trim();
        // 标题行太长多半是正则误伤（比如正文里出现"第一"这种词），截断一下
        if (line.length() > 60) {
            line = line.substring(0, 60) + "...";
        }
        return line.isEmpty() ? "第 " + (start + 1) + " 章" : line;
    }

    /** 按固定字数分块，尽量在段落处收尾 */
    private static List<Chapter> parseByChunk(String content, int chunkSize) {
        List<Chapter> chapters = new ArrayList<>();
        int total = content.length();
        int index = 0;
        int pos = 0;
        while (pos < total) {
            int end = Math.min(pos + chunkSize, total);
            if (end < total) {
                int nl = content.lastIndexOf('\n', end);
                if (nl > pos + chunkSize / 2) {
                    end = nl + 1;
                }
            }
            chapters.add(new Chapter(index, "第 " + (index + 1) + " 节", pos, end));
            index++;
            pos = end;
        }
        return chapters;
    }
}
