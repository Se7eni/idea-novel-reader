package com.github.ideanovel.ui;

import java.util.List;

/**
 * 章内段游标：一章切好的段 + 当前位置。
 *
 * <p>状态栏阅读和弹出框阅读各有各的游标——两处同时开着时位置独立，
 * 用户一次只会盯着一处看，独立反而不会互相打架。
 *
 * <p>纯类、不碰 Swing 和平台 API，游标语义（越界、跳尾、进度文本）
 * 全部可以在探针里直接断言。
 */
public final class ChunkCursor {

    private List<String> chunks = List.of("");
    private int index;

    /** 载入一章并定位。jumpToEnd 为 true 时落在最后一段（从下一章往回翻的落点） */
    public void setChapter(String body, boolean jumpToEnd) {
        chunks = ChapterChunks.split(body);
        index = jumpToEnd ? Math.max(0, chunks.size() - 1) : 0;
    }

    /** 往后翻一段。已在最后一段时返回 false（由调用方决定是否越章） */
    public boolean next() {
        if (index < chunks.size() - 1) {
            index++;
            return true;
        }
        return false;
    }

    /** 往前翻一段。已在第一段时返回 false（由调用方决定是否越章） */
    public boolean prev() {
        if (index > 0) {
            index--;
            return true;
        }
        return false;
    }

    public boolean atFirst() {
        return index <= 0;
    }

    public boolean atLast() {
        return index >= chunks.size() - 1;
    }

    /** 当前段正文（不含进度） */
    public String text() {
        return chunks.get(index);
    }

    /** 状态栏上显示的一整行：正文 + 进度尾巴 */
    public String display() {
        return text() + "  (" + (index + 1) + "/" + chunks.size() + ")";
    }

    /** 占位文案也走同一条渲染路径，进度尾巴保持格式统一 */
    public String displayPlaceholder(String message) {
        return message == null ? "" : message;
    }

    public int index() {
        return index;
    }

    public int size() {
        return chunks.size();
    }
}
