package com.github.ideanovel.source;

import com.github.ideanovel.model.Book;

/**
 * 小说来源。目前有本地文件和网络两种实现，后续要接书源 API 直接实现这个接口即可。
 */
public interface NovelSource {

    /**
     * 加载一本书。
     *
     * @param location 本地文件绝对路径，或网络目录页地址
     */
    Book load(String location) throws Exception;
}
