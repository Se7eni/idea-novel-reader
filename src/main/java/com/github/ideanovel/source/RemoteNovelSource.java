package com.github.ideanovel.source;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.model.Chapter;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.parser.HtmlTextExtractor;
import com.github.ideanovel.settings.NovelSettingsState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络小说源。
 *
 * 打开一个地址时先判断是目录页还是正文页：
 * - 目录页：解析出章节链接列表，正文按需懒加载
 * - 正文页：整页当一章读
 */
public class RemoteNovelSource implements NovelSource {

    private static final Pattern CHAPTER_TEXT = Pattern.compile(
            "第\\s*[0-9零一二三四五六七八九十百千万两]+\\s*[章节回卷篇集]");

    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile(
            "charset\\s*=\\s*([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);

    private static HttpClient client;

    private static synchronized HttpClient http() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    @Override
    public Book load(String url) throws Exception {
        String html = fetch(url);
        String title = HtmlTextExtractor.extractTitle(html);

        Book book = new Book(Book.idOf(SourceType.REMOTE, url),
                title.isEmpty() ? url : title, SourceType.REMOTE, url);

        List<HtmlTextExtractor.Link> chapterLinks = pickChapterLinks(html, url);

        if (chapterLinks.size() >= 3) {
            NovelSettingsState settings = NovelSettingsState.getInstance();
            int limit = settings == null ? 50 : settings.remoteChapterLimit;

            List<Chapter> chapters = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (HtmlTextExtractor.Link link : chapterLinks) {
                String abs = HtmlTextExtractor.resolveUrl(url, link.href);
                if (!seen.add(abs)) {
                    continue;
                }
                Chapter c = new Chapter(chapters.size(), link.text, 0, 0);
                c.setUrl(abs);
                chapters.add(c);
                if (limit > 0 && chapters.size() >= limit) {
                    break;
                }
            }
            book.setChapters(chapters);
            book.setLazyChapters(true);
        } else {
            // 单页正文，没有目录
            String text = HtmlTextExtractor.toText(html);
            book.setContent(text);
            Chapter c = new Chapter(0, book.getTitle(), 0, text.length());
            List<Chapter> chapters = new ArrayList<>();
            chapters.add(c);
            book.setChapters(chapters);
            book.setLazyChapters(false);
        }
        return book;
    }

    /** 拉取单个章节的正文 */
    public String loadChapterBody(String url) throws Exception {
        return HtmlTextExtractor.toText(fetch(url));
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();

        HttpResponse<byte[]> response = http().send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status >= 400) {
            throw new java.io.IOException("请求失败，HTTP " + status + "：" + url);
        }
        byte[] body = response.body();
        Charset charset = resolveCharset(response.headers().firstValue("Content-Type").orElse(""), body);
        return new String(body, charset);
    }

    private static Charset resolveCharset(String contentType, byte[] body) {
        Matcher m = CONTENT_TYPE_CHARSET.matcher(contentType);
        if (m.find()) {
            Charset c = tryCharset(m.group(1));
            if (c != null) {
                return c;
            }
        }
        // header 里没有就看 meta
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher meta = META_CHARSET.matcher(head);
        if (meta.find()) {
            Charset c = tryCharset(meta.group(1));
            if (c != null) {
                return c;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset tryCharset(String name) {
        try {
            return Charset.forName(name.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从一堆链接里挑出像章节的那些 */
    private static List<HtmlTextExtractor.Link> pickChapterLinks(String html, String baseUrl) {
        List<HtmlTextExtractor.Link> all = HtmlTextExtractor.extractLinks(html);
        List<HtmlTextExtractor.Link> strong = new ArrayList<>();
        List<HtmlTextExtractor.Link> weak = new ArrayList<>();

        for (HtmlTextExtractor.Link link : all) {
            String text = link.text;
            if (text.length() > 40) {
                continue;
            }
            if (CHAPTER_TEXT.matcher(text).find()) {
                strong.add(link);
            } else if (looksLikeChapterUrl(link.href) && containsCjk(text) && text.length() >= 2) {
                weak.add(link);
            }
        }
        return strong.size() >= 3 ? strong : weak;
    }

    /** 章节页 URL 通常带数字 id，比如 /book/123/45678.html */
    private static boolean looksLikeChapterUrl(String href) {
        if (href == null) {
            return false;
        }
        String lower = href.toLowerCase();
        if (lower.contains("javascript") || lower.startsWith("#")) {
            return false;
        }
        int digits = 0;
        for (char c : lower.toCharArray()) {
            if (Character.isDigit(c)) {
                digits++;
            }
        }
        return digits >= 3 && (lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith("/") || lower.contains("read") || lower.contains("chapter"));
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
