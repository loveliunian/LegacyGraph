package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识断言（Knowledge Claim）实体。
 * <p>
 * 统一断言模型：所有 Extractor/Agent 的输出先表达为带证据的 Claim，
 * 再由 KnowledgeCompiler 投影回现有图谱。Claim 不是图节点/边的替代，
 * 而是让"事实如何成立"可追溯、可反证、可编译。
 * </p>
 *
 * <p>状态流转：</p>
 * <pre>
 * PENDING_CONFIRM → CONFIRMED / CONFLICTED / REJECTED / STALE
 * </pre>
 */
@Data
@TableName("lg_knowledge_claim")
public class KnowledgeClaim {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    /** 主体类型：Feature / ApiEndpoint / Method / BusinessObject / BusinessRule 等 */
    private String subjectType;

    /** 主体标识：feature:订单创建 / POST /orders / Method:OrderController#create */
    private String subjectKey;

    /** 谓词：HANDLED_BY / CALLS / READS / WRITES / EXPOSED_BY / IMPLEMENTS / MATERIALIZED_AS / ENFORCES_RULE */
    private String predicate;

    /** 客体类型（可为空，如纯值断言） */
    private String objectType;

    /** 客体标识 */
    private String objectKey;

    /** 客体值（用于纯值断言，如规则表达式） */
    private String objectValue;

    /** 限定符：method/path/sql/hash/role/status/env 等，JSONB */
    private String qualifiers;

    /** 关联证据 ID 列表，JSONB 数组 */
    private String evidenceIds;

    /** 支持本条 Claim 的其他 Claim ID 列表 */
    private String supportingClaimIds;

    /** 反驳本条 Claim 的其他 Claim ID 列表 */
    private String contradictingClaimIds;

    /** 来源类型：CODE / DB / DOC / RUNTIME / TEST / AI / DOC_AI / CODE_AI / AI_INFERENCE */
    private String sourceType;

    /** 提取器：JavaControllerExtractor / DocUnderstandingAgent 等 */
    private String extractor;

    /** 置信度 0~1，NUMERIC(5,4) */
    private BigDecimal confidence;

    /** 状态：PENDING_CONFIRM / CONFIRMED / CONFLICTED / REJECTED / STALE */
    private String status;

    /** 谱系：由哪些 Claim 合并或派生，JSONB 数组 */
    private String lineage;

    /** 编译后的节点 ID（KnowledgeCompiler 编译结果） */
    private String compiledNodeId;

    /** 编译后的边 ID */
    private String compiledEdgeId;

    /** 编译状态：NEW / COMPILED / FAILED / SKIPPED */
    private String compileStatus;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
