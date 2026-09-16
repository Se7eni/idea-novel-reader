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
        List<int[]> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(new int[]{matcher.start(), matcher.end()});
            // 章节多的时候没必要全扫，提前止损
            if (starts.size() > 20000) {
                break;
            }
        }
        if (starts.isEmpty()) {
            return chapters;
        }

        // 第一处匹配之前若有内容，单独作为"开头"
        if (starts.get(0)[0] > 0) {
            String head = content.substring(0, starts.get(0)[0]).trim();
            if (head.length() > 10) {
                chapters.add(new Chapter(0, "开头", 0, starts.get(0)[0]));
            }
        }

        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i)[0];
            int titleEnd = starts.get(i)[1];
            int end = (i + 1 < starts.size()) ? starts.get(i + 1)[0] : content.length();

            String title = extractTitle(content, start, titleEnd);
            Chapter c = new Chapter(chapters.size(), title, start, end);
            chapters.add(c);
        }
        return chapters;
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
