package com.github.ideanovel.model;

/**
 * 小说来源类型。
 */
public enum SourceType {
    /** 本地 txt 文件 */
    LOCAL,
    /** 网络小说 */
    REMOTE;

    public String displayName() {
        return this == LOCAL ? "本地" : "网络";
    }
}
