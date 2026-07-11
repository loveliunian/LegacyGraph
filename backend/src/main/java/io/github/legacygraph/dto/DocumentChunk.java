package io.github.legacygraph.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化切块结果 DTO — 由 {@code StructureAwareChunkService} 产出，供向量化与检索使用。
 * <p>
 * 字段说明见 spec 5.1：
 * <ul>
 *   <li>content：块内容（含 headingPath 前缀 {@code [h1 > h2 > ...]}）</li>
 *   <li>sourceLocation：来源位置（取该块第一个元素的 sourceLocation）</li>
 *   <li>headingPath：章节路径</li>
 *   <li>chunkIndex：块序号（从 0 递增）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    /** 块内容（含 headingPath 前缀） */
    private String content;

    /** 来源位置（取该块第一个元素的 sourceLocation） */
    private String sourceLocation;

    /** 章节路径 */
    private List<String> headingPath;

    /** 块序号 */
    private int chunkIndex;
}
