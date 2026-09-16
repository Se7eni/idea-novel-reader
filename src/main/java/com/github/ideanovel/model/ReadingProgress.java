package com.github.ideanovel.model;

/**
 * 阅读进度，会被持久化到 ideanovel.xml。
 * 字段必须保持 public 且有无参构造，XmlSerializer 才能正常存取。
 */
public class ReadingProgress {

    public String bookId;
    public String title;
    public SourceType type = SourceType.LOCAL;
    public String location;
    public int chapterIndex;
    /** 当前章节内的滚动比例 0~1 */
    public float chapterRatio;
    public long updatedAt;

    public ReadingProgress() {
    }

    public ReadingProgress(String bookId, String title, SourceType type, String location) {
        this.bookId = bookId;
        this.title = title;
        this.type = type;
        this.location = location;
    }

    public SourceType safeType() {
        return type == null ? SourceType.LOCAL : type;
    }
}
