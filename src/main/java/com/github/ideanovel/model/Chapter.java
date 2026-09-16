package com.github.ideanovel.model;

/**
 * 一个章节。对本地小说，通过 start/end 偏移定位正文；对网络小说，通过 url 定位。
 */
public class Chapter {

    private int index;
    private String title;

    /** 正文起始偏移（含标题行） */
    private int startOffset;
    /** 正文结束偏移（不含） */
    private int endOffset;

    /** 网络章节的绝对地址，本地章节为 null */
    private String url;

    /** 网络章节懒加载过来的正文内容，加载后置位 */
    private transient String remoteContent;

    public Chapter() {
    }

    public Chapter(int index, String title, int startOffset, int endOffset) {
        this.index = index;
        this.title = title;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(int startOffset) {
        this.startOffset = startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(int endOffset) {
        this.endOffset = endOffset;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRemoteContent() {
        return remoteContent;
    }

    public void setRemoteContent(String remoteContent) {
        this.remoteContent = remoteContent;
    }

    public boolean isRemoteLoaded() {
        return remoteContent != null && !remoteContent.isEmpty();
    }

    /** 章节字数（估算，用于状态栏显示） */
    public int length() {
        if (remoteContent != null) {
            return remoteContent.length();
        }
        return Math.max(0, endOffset - startOffset);
    }

    @Override
    public String toString() {
        return getTitle();
    }
}
