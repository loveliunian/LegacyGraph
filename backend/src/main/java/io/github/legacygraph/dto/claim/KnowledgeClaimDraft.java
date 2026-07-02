package io.github.legacygraph.dto.claim;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * KnowledgeClaim 草稿 DTO — Extractor/Agent 写入前的内存对象。
 * 由 Agent 的 toClaimDrafts() 方法生成，KnowledgeClaimService.upsertDraft() 消费。
 */
@Data
@Builder
public class KnowledgeClaimDraft {

    private String projectId;

    private String versionId;

    /** 主体类型：Feature / ApiEndpoint / Method / BusinessObject / BusinessRule 等 */
    private String subjectType;

    /** 主体标识 */
    private String subjectKey;

    /** 谓词 */
    private String predicate;

    /** 客体类型 */
    private String objectType;

    /** 客体标识 */
    private String objectKey;

    /** 客体值 */
    private String objectValue;

    /** 限定符：method/path/sql/hash/role/status/env 等 */
    private Map<String, Object> qualifiers;

    /** 关联证据 ID 列表 */
    private List<String> evidenceIds;

    /** 来源类型 */
    private String sourceType;

    /** 提取器名称 */
    private String extractor;

    /** 置信度 0~1 */
    private BigDecimal confidence;

    /** 关联的支持性 Claim draft 键（用于构建 lineage） */
    private List<String> supportingClaimKeys;
}
