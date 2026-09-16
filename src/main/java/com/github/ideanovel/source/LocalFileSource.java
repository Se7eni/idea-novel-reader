package com.github.ideanovel.source;

import com.github.ideanovel.model.Book;
import com.github.ideanovel.model.Chapter;
import com.github.ideanovel.model.SourceType;
import com.github.ideanovel.parser.ChapterParser;
import com.github.ideanovel.parser.EncodingDetector;
import com.github.ideanovel.settings.NovelSettingsState;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 本地 txt 小说源：自动识别编码，清洗排版，按章节切分。
 */
public class LocalFileSource implements NovelSource {

    @Override
    public Book load(String location) throws IOException {
        Path path = java.nio.file.Paths.get(location);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + location);
        }
        if (Files.isDirectory(path)) {
            throw new IOException("请选择 txt 文件，而不是目录: " + location);
        }

        byte[] bytes = Files.readAllBytes(path);
        Charset charset = EncodingDetector.detect(bytes);
        String text = new String(bytes, charset);
        text = stripBom(text);
        text = normalize(text);

        String fileName = path.getFileName().toString();
        String title = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        Book book = new Book(Book.idOf(SourceType.LOCAL, path.toAbsolutePath().toString()),
                title, SourceType.LOCAL, path.toAbsolutePath().toString());
        book.setContent(text);
        book.setLazyChapters(false);

        NovelSettingsState settings = NovelSettingsState.getInstance();
        boolean useRegex = settings == null || settings.useRegexChapter;
        String regex = settings == null ? ChapterParser.DEFAULT_REGEX : settings.chapterRegex;
        int chunk = settings == null ? 4000 : settings.chunkSize;

        List<Chapter> chapters = ChapterParser.parse(text, useRegex, regex, chunk);
        book.setChapters(chapters);
        return book;
    }

    private static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    /** 统一换行、去掉行尾空白、压缩连续空行，让阅读区看起来舒服些 */
    private static String normalize(String s) {
        String r = s.replace("\r\n", "\n").replace('\r', '\n');
        r = r.replace('\u00A0', ' ').replace('\u3000', ' ');
        StringBuilder sb = new StringBuilder(r.length());
        int blank = 0;
        for (String line : r.split("\n", -1)) {
            String t = line.replaceAll("[ \t]+$", "");
            if (t.trim().isEmpty()) {
                blank++;
                if (blank > 1) {
                    continue;
                }
            } else {
                blank = 0;
            }
            sb.append(t).append('\n');
        }
        return sb.toString();
    }
}
