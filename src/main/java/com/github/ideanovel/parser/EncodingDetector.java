package com.github.ideanovel.parser;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 小说 txt 编码识别。国内站点下载的 txt 大量是 GBK，直接按 UTF-8 读会满屏乱码，
 * 所以按 BOM -> 严格 UTF-8 -> GBK 的顺序探测。
 */
public final class EncodingDetector {

    private EncodingDetector() {
    }

    public static Charset detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }

        // 1. BOM 判断
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        // 2. 严格 UTF-8 解码，能过就是 UTF-8
        if (isValidUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }

        // 3. 否则按中文环境最常见的 GBK 处理
        Charset gbk = charsetOrNull("GBK");
        if (gbk != null) {
            return gbk;
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes));
            // 纯 ASCII 文件按 UTF-8 和 GBK 读都一样，交给 UTF-8
            return out.length() > 0;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }
}
