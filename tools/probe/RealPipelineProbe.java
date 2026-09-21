import com.github.ideanovel.model.Chapter;
import com.github.ideanovel.parser.ChapterParser;
import com.github.ideanovel.parser.EncodingDetector;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按真实管线验证章节去重：读字节 → 识别编码 → stripBom → normalize → parse。
 *
 * 为什么要走 normalize：全角空格 \u3000 在这里被换成普通空格，
 * 缩进的重复标题行这才被 ^\s* 匹配上。之前直接喂原文给 ChapterParser，
 * 绕过了这一步，等于没复现用户看到的场景。
 *
 * normalize / stripBom 用反射调真实实现，不在探针里重写一遍。
 */
public class RealPipelineProbe {

    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        Charset charset = EncodingDetector.detect(bytes);
        String raw = new String(bytes, charset);

        Class<?> lfs = Class.forName("com.github.ideanovel.source.LocalFileSource");
        Method stripBom = lfs.getDeclaredMethod("stripBom", String.class);
        Method normalize = lfs.getDeclaredMethod("normalize", String.class);
        stripBom.setAccessible(true);
        normalize.setAccessible(true);

        String text = (String) stripBom.invoke(null, raw);
        text = (String) normalize.invoke(null, text);

        System.out.println("normalize 后字符数 = " + text.length());

        List<Chapter> chapters = ChapterParser.parse(
                text, true, ChapterParser.DEFAULT_REGEX, 4000);
        System.out.println("章节数 = " + chapters.size());

        Map<String, List<Integer>> byTitle = new LinkedHashMap<>();
        for (Chapter c : chapters) {
            byTitle.computeIfAbsent(c.getTitle(), k -> new ArrayList<>()).add(c.getIndex());
        }
        List<String> dup = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byTitle.entrySet()) {
            if (e.getValue().size() > 1) {
                dup.add(e.getKey());
            }
        }
        System.out.println("重复标题组数 = " + dup.size());
        for (String d : dup) {
            System.out.println("    重复: " + d);
        }

        System.out.println("--- 84~86 章明细 ---");
        for (Chapter c : chapters) {
            if (c.getTitle().matches("第8[4-6]章.*")) {
                System.out.printf("    [%d] %s  %d~%d (%d 字)%n",
                        c.getIndex(), c.getTitle(),
                        c.getStartOffset(), c.getEndOffset(),
                        c.getEndOffset() - c.getStartOffset());
            }
        }
        System.out.println("--- 最后 3 章 ---");
        int n = chapters.size();
        for (int i = Math.max(0, n - 3); i < n; i++) {
            Chapter c = chapters.get(i);
            System.out.printf("    [%d] %s  %d~%d (%d 字)%n",
                    c.getIndex(), c.getTitle(),
                    c.getStartOffset(), c.getEndOffset(),
                    c.getEndOffset() - c.getStartOffset());
        }

        // 章末是否残留别的章节标题（去重后不该有）
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                ChapterParser.DEFAULT_REGEX, java.util.regex.Pattern.MULTILINE);
        int leaked = 0;
        for (Chapter c : chapters) {
            String body = text.substring(Math.max(0, c.getStartOffset()),
                    Math.min(text.length(), c.getEndOffset()));
            // 先跳过开头空白（起点可能包含标题前的空行），第一行才是本章标题
            int k = 0;
            while (k < body.length() && Character.isWhitespace(body.charAt(k))) {
                k++;
            }
            String trimmed = body.substring(k);
            int firstNl = trimmed.indexOf('\n');
            String tail = firstNl < 0 ? "" : trimmed.substring(firstNl + 1);
            java.util.regex.Matcher mm = head.matcher(tail);
            if (mm.find()) {
                leaked++;
                if (leaked <= 5) {
                    System.out.println("    章末残留标题: [" + c.getIndex() + "] " + c.getTitle()
                            + "  -> " + tail.substring(Math.max(0, mm.start() - 5),
                            Math.min(tail.length(), mm.start() + 30)).replace('\n', '|'));
                }
            }
        }
        System.out.println("章末残留别的标题的章节数 = " + leaked);

        // 内容是否缺失：各章区间是否首尾相接、是否覆盖全文
        long covered = 0;
        for (Chapter c : chapters) {
            covered += Math.max(0, c.getEndOffset() - c.getStartOffset());
        }
        System.out.println("覆盖字符 = " + covered + " / " + text.length()
                + " (" + (covered * 100 / Math.max(1, text.length())) + "%)");
    }
}
