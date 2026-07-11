package io.github.legacygraph.service.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * G-03: FastParsingStrategy — 快速解析策略。
 * <p>
 * 处理纯文本类文档：text/*, markdown, 源码, JSON, YAML, XML 等。
 * 直接返回原始内容，不做任何转换。这是默认兜底策略。
 * </p>
 */
@Slf4j
@Component
public class FastParsingStrategy implements DocumentParsingStrategy {

    @Override
    public String strategyName() {
        return "FAST";
    }

    @Override
    public boolean supports(String mimeType, long fileSize) {
        if (mimeType == null) {
            return true; // 兜底：未知类型走 FAST
        }
        String mt = mimeType.toLowerCase();
        // 纯文本类
        return mt.startsWith("text/")
                || mt.contains("markdown")
                || mt.contains("json")
                || mt.contains("yaml")
                || mt.contains("xml")
                || mt.contains("javascript")
                || mt.contains("java")
                || mt.contains("python")
                || mt.contains("sql")
                || mt.contains("shell")
                || mt.contains("plain");
    }

    @Override
    public String parse(String content, String mimeType) {
        // 快速解析：直接返回原始内容
        return content != null ? content : "";
    }
}
