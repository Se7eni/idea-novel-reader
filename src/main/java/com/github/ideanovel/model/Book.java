package com.github.ideanovel.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一本书。本地小说会把全文读进内存（普通 txt 一般几 MB，可以接受）；
 * 网络小说只保存目录，正文按章节懒加载。
 */
public class Book {

    private String id;
    private String title;
    private SourceType type;
    /** 本地文件的绝对路径，或网络目录页地址 */
    private String location;
    /** 本地小说全文 */
    private String content;
    private List<Chapter> chapters = new ArrayList<>();
    /** 网络小说：正文需要按章异步拉取 */
    private boolean lazyChapters;

    public Book() {
    }

    public Book(String id, String title, SourceType type, String location) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.location = location;
    }

    /** 生成稳定的书籍 id，用于进度持久化 */
    public static String idOf(SourceType type, String location) {
        String raw = type.name() + ":" + location;
        return Integer.toHexString(raw.hashCode());
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title == null ? "未命名" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    public void setChapters(List<Chapter> chapters) {
        this.chapters = chapters == null ? new ArrayList<>() : chapters;
    }

    public boolean isLazyChapters() {
        return lazyChapters;
    }

    public void setLazyChapters(boolean lazyChapters) {
        this.lazyChapters = lazyChapters;
    }

    public Chapter chapterAt(int index) {
        if (index < 0 || index >= chapters.size()) {
            return null;
        }
        return chapters.get(index);
    }

    /** 取某一章的正文；本地走偏移切片，网络走懒加载内容 */
    public String chapterText(int index) {
        Chapter c = chapterAt(index);
        if (c == null) {
            return "";
        }
        if (c.getRemoteContent() != null) {
            return c.getRemoteContent();
        }
        if (content == null) {
            return "";
        }
        int start = Math.max(0, Math.min(c.getStartOffset(), content.length()));
        int end = Math.max(start, Math.min(c.getEndOffset(), content.length()));
        return content.substring(start, end);
    }

    public int totalChars() {
        if (content != null) {
            return content.length();
        }
        int sum = 0;
        for (Chapter c : chapters) {
            sum += c.length();
        }
        return sum;
    }
}
