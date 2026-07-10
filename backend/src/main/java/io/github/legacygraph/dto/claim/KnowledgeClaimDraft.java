package io.github.legacygraph.dto.claim;

import io.github.legacygraph.agent.DocUnderstandingAgent;
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

    /** 谱系 JSON 字符串（JSONB 数组），记录推导规则；为空时落库为 "[]" */
    private String lineage;

    /**
     * 临时证据引用列表（不持久化）。
     * <p>
     * 由 DocUnderstandingAgent 填充 LLM 返回的 EvidenceRef，KnowledgeClaimService
     * 在 upsertDrafts() 中据此创建真实 Evidence 记录，用真实 UUID 填充 {@link #evidenceIds}。
     * 不参与 Claim 去重键，不直接落库。
     * </p>
     */
    private List<DocUnderstandingAgent.EvidenceRef> transientEvidenceRefs;

    /**
     * 临时内容摘要（不持久化）。
     * <p>
     * 用于 BusinessDomain 等无 EvidenceRef 列表但含 evidenceText 的场景，
     * 作为 Evidence.contentExcerpt 的回退来源。
     * </p>
     */
    private String transientContentExcerpt;
}
