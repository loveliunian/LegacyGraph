package io.github.legacygraph.service.document;

import io.github.legacygraph.dto.DocumentChunk;
import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知切块服务（spec 5.1–5.3）。
 * <p>
 * 输入 {@link DocumentElement} 列表，输出 {@link DocumentChunk} 列表。切块规则：
 * <ul>
 *   <li>不跨一级标题合并（遇到新的 TITLE 且 headingPath 只有 1 个元素时，开始新块）</li>
 *   <li>TABLE 元素单独成块</li>
 *   <li>CODE_BLOCK 元素单独成块</li>
 *   <li>NARRATIVE_TEXT：连续的同级标题下的段落合并，直到达到 maxChars 或遇到新的 TABLE/CODE_BLOCK/一级标题</li>
 *   <li>超长段落按句子边界切分（中文 。？！，英文 . ? !）</li>
 * </ul>
 * 每块 content 前缀为 {@code [headingPath[0] > headingPath[1] > ...]}，sourceLocation 取该块第一个元素的 sourceLocation。
 */
@Slf4j
@Service
public class StructureAwareChunkService {

    /** 大文档阈值（字符），超过则使用更小的 maxChars */
    static final int LARGE_DOC_THRESHOLD = 50_000;
    /** 中文档阈值（字符） */
    static final int MEDIUM_DOC_THRESHOLD = 20_000;
    /** 大文档 maxChars */
    static final int LARGE_DOC_MAX_CHARS = 2500;
    /** 中文档 maxChars */
    static final int MEDIUM_DOC_MAX_CHARS = 1800;

    private final int defaultMaxChars;

    public StructureAwareChunkService(
            @Value("${legacygraph.document.partition.max-chars:2500}") int defaultMaxChars) {
        this.defaultMaxChars = defaultMaxChars > 0 ? defaultMaxChars : 2500;
    }

    /**
     * 使用默认 maxChars 切块。
     */
    public List<DocumentChunk> chunk(List<DocumentElement> elements) {
        return chunk(elements, defaultMaxChars);
    }

    /**
     * 使用指定 maxChars 切块。
     *
     * @param elements  结构化元素列表
     * @param maxChars  每块最大字符数（不含 headingPath 前缀）
     * @return 结构化切块列表
     */
    public List<DocumentChunk> chunk(List<DocumentElement> elements, int maxChars) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (elements == null || elements.isEmpty()) {
            return chunks;
        }
        int effectiveMax = maxChars > 0 ? maxChars : defaultMaxChars;

        // 当前合并缓冲
        StringBuilder buffer = new StringBuilder();
        List<String> bufferHeadingPath = null;
        String bufferFirstSourceLocation = null;
        int chunkIndex = 0;

        for (DocumentElement element : elements) {
            DocumentElement.Type type = element.getType();
            List<String> headingPath = element.getHeadingPath();

            // 一级标题：硬边界，flush 当前缓冲后开始新段
            if (type == DocumentElement.Type.TITLE && isLevelOneHeading(headingPath)) {
                chunkIndex = flushBuffer(chunks, buffer, bufferHeadingPath,
                        bufferFirstSourceLocation, chunkIndex, effectiveMax);
                buffer.setLength(0);
                bufferHeadingPath = copyPath(headingPath);
                bufferFirstSourceLocation = element.getSourceLocation();
                continue;
            }

            // TABLE：单独成块
            if (type == DocumentElement.Type.TABLE) {
                chunkIndex = flushBuffer(chunks, buffer, bufferHeadingPath,
                        bufferFirstSourceLocation, chunkIndex, effectiveMax);
                buffer.setLength(0);
                bufferHeadingPath = null;
                bufferFirstSourceLocation = null;
                chunks.add(buildChunk(element.getText(), headingPath,
                        element.getSourceLocation(), chunkIndex++));
                continue;
            }

            // CODE_BLOCK：单独成块
            if (type == DocumentElement.Type.CODE_BLOCK) {
                chunkIndex = flushBuffer(chunks, buffer, bufferHeadingPath,
                        bufferFirstSourceLocation, chunkIndex, effectiveMax);
                buffer.setLength(0);
                bufferHeadingPath = null;
                bufferFirstSourceLocation = null;
                chunks.add(buildChunk(element.getText(), headingPath,
                        element.getSourceLocation(), chunkIndex++));
                continue;
            }

            // NARRATIVE_TEXT 或非一级 TITLE：合并到缓冲
            // headingPath 变化时 flush（不同章节的段落不合并）
            if (bufferHeadingPath != null && !samePath(bufferHeadingPath, headingPath)) {
                chunkIndex = flushBuffer(chunks, buffer, bufferHeadingPath,
                        bufferFirstSourceLocation, chunkIndex, effectiveMax);
                buffer.setLength(0);
                bufferHeadingPath = copyPath(headingPath);
                bufferFirstSourceLocation = element.getSourceLocation();
            } else if (bufferHeadingPath == null) {
                bufferHeadingPath = copyPath(headingPath);
                bufferFirstSourceLocation = element.getSourceLocation();
            }

            String text = element.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(text);

            // 超过 maxChars 时按句子边界切分
            if (buffer.length() > effectiveMax) {
                List<String> pieces = splitBySentences(buffer.toString(), effectiveMax);
                // 前 n-1 片作为独立 chunk 发出，最后一片保留在缓冲中
                // （等待后续同章节段落追加，或最终 flush 时再处理）
                for (int i = 0; i < pieces.size() - 1; i++) {
                    chunks.add(buildChunk(pieces.get(i), bufferHeadingPath,
                            bufferFirstSourceLocation, chunkIndex++));
                }
                // 最后一片保留在缓冲
                buffer.setLength(0);
                buffer.append(pieces.get(pieces.size() - 1));
            }
        }
        // flush 剩余缓冲
        flushBuffer(chunks, buffer, bufferHeadingPath, bufferFirstSourceLocation,
                chunkIndex, effectiveMax);
        return chunks;
    }

    /**
     * 根据文档总内容大小动态决定 maxChars（spec 5.5 分级 chunk size）。
     * <ul>
     *   <li>&gt;50KB：2500</li>
     *   <li>&gt;20KB：1800</li>
     *   <li>其他：2500（默认）</li>
     * </ul>
     *
     * @param totalContentSize 文档总字符数
     * @return 该文档应使用的 maxChars
     */
    public int determineMaxChars(int totalContentSize) {
        if (totalContentSize > LARGE_DOC_THRESHOLD) {
            return LARGE_DOC_MAX_CHARS;
        }
        if (totalContentSize > MEDIUM_DOC_THRESHOLD) {
            return MEDIUM_DOC_MAX_CHARS;
        }
        return defaultMaxChars;
    }

    /**
     * 计算元素列表的文本总长度（用于 determineMaxChars）。
     */
    public int totalTextSize(List<DocumentElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (DocumentElement e : elements) {
            if (e.getText() != null) {
                total += e.getText().length();
            }
        }
        return total;
    }

    // ==================== 内部方法 ====================

    private boolean isLevelOneHeading(List<String> headingPath) {
        return headingPath != null && headingPath.size() == 1;
    }

    private List<String> copyPath(List<String> path) {
        return path != null ? new ArrayList<>(path) : new ArrayList<>();
    }

    private boolean samePath(List<String> a, List<String> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    /** flush 缓冲为一个或多个 chunk（超长时按句子切分），返回更新后的 chunkIndex。 */
    private int flushBuffer(List<DocumentChunk> chunks, StringBuilder buffer,
                            List<String> headingPath, String sourceLocation,
                            int chunkIndex, int maxChars) {
        if (buffer.length() == 0) {
            return chunkIndex;
        }
        String text = buffer.toString();
        if (text.length() <= maxChars) {
            chunks.add(buildChunk(text, headingPath, sourceLocation, chunkIndex++));
        } else {
            List<String> pieces = splitBySentences(text, maxChars);
            for (String piece : pieces) {
                if (!piece.isEmpty()) {
                    chunks.add(buildChunk(piece, headingPath, sourceLocation, chunkIndex++));
                }
            }
        }
        return chunkIndex;
    }

    /** 构建 DocumentChunk，content 前缀为 [headingPath]。 */
    private DocumentChunk buildChunk(String text, List<String> headingPath,
                                     String sourceLocation, int chunkIndex) {
        String prefix = buildHeadingPrefix(headingPath);
        String content = prefix.isEmpty() ? text : prefix + "\n" + text;
        return DocumentChunk.builder()
                .content(content)
                .sourceLocation(sourceLocation)
                .headingPath(copyPath(headingPath))
                .chunkIndex(chunkIndex)
                .build();
    }

    /** 生成 headingPath 前缀：[h1 > h2 > h3]。无 headingPath 时返回空串。 */
    private String buildHeadingPrefix(List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return "";
        }
        return "[" + String.join(" > ", headingPath) + "]";
    }

    /**
     * 按句子边界切分文本，每片不超过 maxChars。
     * <p>
     * 句子边界：中文 。？！，英文 . ? !（边界字符保留在前一句末尾）。
     * 单句超长时在 maxChars 处硬切。
     *
     * @param text     待切分文本
     * @param maxChars 每片最大字符数
     * @return 切分后的文本片段列表
     */
    static List<String> splitBySentences(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        if (text.length() <= maxChars) {
            result.add(text);
            return result;
        }

        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int boundary = findNextSentenceBoundary(text, i);
            // 边界后一位（包含分隔符）
            String sentence = text.substring(i, boundary);
            i = boundary;

            // 如果当前片加上这句话不超限，追加
            if (current.length() == 0) {
                current.append(sentence);
            } else if (current.length() + 1 + sentence.length() <= maxChars) {
                current.append('\n').append(sentence);
            } else {
                // 当前片已满，flush 并开始新片
                result.add(current.toString());
                current.setLength(0);
                current.append(sentence);
            }

            // 单句超长：硬切
            while (current.length() > maxChars) {
                result.add(current.substring(0, maxChars));
                current = new StringBuilder(current.substring(maxChars));
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 从 fromIndex 开始查找下一个句子边界位置（边界字符的下一个字符索引）。
     * 支持中文句号（。）问号（？）叹号（！）和英文 . ? !。
     * 找不到时返回 text.length()（剩余全部作为一句）。
     */
    private static int findNextSentenceBoundary(String text, int fromIndex) {
        for (int i = fromIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '？' || c == '！'
                    || c == '.' || c == '?' || c == '!') {
                // 返回边界字符的下一位
                return Math.min(i + 1, text.length());
            }
            // 换行也可作为边界
            if (c == '\n') {
                return Math.min(i + 1, text.length());
            }
        }
        return text.length();
    }
}
