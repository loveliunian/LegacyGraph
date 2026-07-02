package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一证据记录 — 所有 Extractor/AI Agent 产出的证据都通过此结构流转。
 * <p>
 * 与数据库实体 {@code io.github.legacygraph.entity.Evidence} 解耦：
 * Writer 负责将 EvidenceRecord 转换为实体并落库+关联。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRecord {

    /** 项目ID */
    private String projectId;

    /** 扫描版本ID */
    private String versionId;

    /** 证据类型：code / sql / doc / ui / test / runtime / ai */
    private String evidenceType;

    /** 来源路径（文件路径） */
    private String sourcePath;

    /** 来源名称（如类名、方法名） */
    private String sourceName;

    /** 起始行号 */
    private Integer startLine;

    /** 结束行号 */
    private Integer endLine;

    /** 内容哈希（用于去重） */
    private String contentHash;

    /** 内容摘要 */
    private String summary;

    /** 完整内容 */
    private String content;

    /** 元数据（JSON） */
    private String metadata;

    /** AST 路径 */
    private String astPath;

    /** SQL 哈希（SQL 证据专用） */
    private String sqlHash;

    /**
     * 证据隐私级别（见 doc/架构与三类图谱AI优化建议.md 4.4）
     * public / internal / confidential / secret
     */
    private String privacyLevel;

    /**
     * 脱敏策略：none / mask / hash / drop
     */
    private String redactionPolicy;

}
