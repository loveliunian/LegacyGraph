package io.github.legacygraph.service.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * G-03: LayoutParsingStrategy — 版面解析策略。
 * <p>
 * 处理带版面结构的文档：PDF, Word (doc/docx), HTML, PPT 等。
 * 去除标签/控制字符，保留段落结构，输出纯文本+段落分隔。
 * </p>
 * <p>
 * 当前为轻量实现（正则去标签），未来可替换为 Apache Tika / PDFBox 等专业解析库。
 * </p>
 */
@Slf4j
@Component
public class LayoutParsingStrategy implements DocumentParsingStrategy {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_SCRIPT = Pattern.compile(
            "<(script|style)[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    @Override
    public String strategyName() {
        return "LAYOUT";
    }

    @Override
    public boolean supports(String mimeType, long fileSize) {
        if (mimeType == null) {
            return false;
        }
        String mt = mimeType.toLowerCase();
        return mt.contains("pdf")
                || mt.contains("msword")
                || mt.contains("officedocument.wordprocessing")
                || mt.contains("officedocument.presentation")
                || mt.contains("html")
                || mt.contains("ppt");
    }

    @Override
    public String parse(String content, String mimeType) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String result = content;
        // 判断是 HTML 还是其他版面文档
        String mt = mimeType != null ? mimeType.toLowerCase() : "";
        if (mt.contains("html") || result.contains("<") && result.contains(">")) {
            result = parseHtml(result);
        } else {
            // PDF/Word 等：去除控制字符，保留换行结构
            result = CONTROL_CHARS.matcher(result).replaceAll("");
        }
        log.debug("LayoutParsingStrategy: mimeType={}, inputLen={}, outputLen={}",
                mimeType, content.length(), result.length());
        return result.trim();
    }

    /** HTML 解析：去标签、去 script/style、保留段落 */
    private String parseHtml(String html) {
        String text = HTML_SCRIPT.matcher(html).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        text = CONTROL_CHARS.matcher(text).replaceAll("");
        // 压缩多余空行
        text = MULTI_BLANK.matcher(text).replaceAll("\n\n");
        return text;
    }
}
