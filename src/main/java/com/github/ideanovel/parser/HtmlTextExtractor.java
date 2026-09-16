package com.github.ideanovel.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 HTML 处理：不引第三方依赖，用正则把网页正文抠出来。
 * 目标是够用，不是做一个完整的浏览器引擎。
 */
public final class HtmlTextExtractor {

    /** 目录页里常见的干扰文案，直接丢掉 */
    private static final String[] NOISE = {
            "上一章", "下一章", "返回目录", "目录", "加入书签", "推荐票", "手机阅读",
            "本章未完", "点击阅读", "小说", "首页", "书架", "登录", "注册", "举报",
            "版权", "Copyright", "copyright", "请记住", "最新网址"
    };

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE);

    private HtmlTextExtractor() {
    }

    public static class Link {
        public final String href;
        public final String text;

        public Link(String href, String text) {
            this.href = href;
            this.text = text;
        }
    }

    /** 抽取网页标题 */
    public static String extractTitle(String html) {
        if (html == null) {
            return "";
        }
        Matcher m = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String t = unescape(m.group(1)).trim();
            if (!t.isEmpty()) {
                // 很多站点标题带"_某某小说网"，去掉尾巴
                int cut = t.lastIndexOf('_');
                if (cut > 3) {
                    t = t.substring(0, cut).trim();
                }
                return t;
            }
        }
        Matcher h1 = Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (h1.find()) {
            return stripTags(h1.group(1)).trim();
        }
        return "";
    }

    /** 网页正文转纯文本 */
    public static String toText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?is)<script[\\s\\S]*?</script>", " ");
        s = s.replaceAll("(?is)<style[\\s\\S]*?</style>", " ");
        s = s.replaceAll("(?is)<!--[\\s\\S]*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</?(p|div|tr|li|h[1-6]|blockquote)[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = unescape(s);

        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String raw : s.split("\n", -1)) {
            String line = raw.replace('\u00A0', ' ')
                    .replaceAll("^[ \t]+", "")
                    .replaceAll("[ \t]+$", "")
                    .trim();
            if (line.isEmpty()) {
                blank++;
                if (blank > 1) {
                    continue;
                }
                continue;
            }
            if (isNoise(line)) {
                continue;
            }
            blank = 0;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 抽取所有链接 */
    public static List<Link> extractLinks(String html) {
        List<Link> links = new ArrayList<>();
        if (html == null) {
            return links;
        }
        Matcher m = LINK_PATTERN.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            String text = unescape(stripTags(m.group(2))).trim();
            if (href.isEmpty() || text.isEmpty()) {
                continue;
            }
            if (href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            links.add(new Link(href, text));
        }
        return links;
    }

    /** 把相对地址补全成绝对地址 */
    public static String resolveUrl(String base, String relative) {
        if (relative == null) {
            return null;
        }
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        try {
            java.net.URI baseUri = java.net.URI.create(base);
            if (relative.startsWith("//")) {
                return baseUri.getScheme() + ":" + relative;
            }
            if (relative.startsWith("/")) {
                return baseUri.getScheme() + "://" + baseUri.getHost() + relative;
            }
            String path = baseUri.getPath();
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "/";
            return baseUri.getScheme() + "://" + baseUri.getHost() + dir + relative;
        } catch (Exception e) {
            return relative;
        }
    }

    private static boolean isNoise(String line) {
        if (line.length() < 4 && !isCjk(line)) {
            return true;
        }
        for (String n : NOISE) {
            if (line.contains(n) && line.length() < 30) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static String stripTags(String s) {
        return s.replaceAll("(?s)<[^>]+>", "").trim();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&ldquo;", "\u201C")
                .replace("&rdquo;", "\u201D")
                .replace("&mdash;", "\u2014")
                .replace("&hellip;", "\u2026");
    }
}
