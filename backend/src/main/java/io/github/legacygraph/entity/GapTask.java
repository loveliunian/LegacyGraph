package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识缺口任务（GapTask）实体。
 * <p>
 * 由 GapFinderService（确定性规则）和 GapFinderAgent（LLM 增强）生成，
 * 表示当前图谱中缺失、矛盾或低置信的部分，提示下一轮补证方向。
 * </p>
 *
 * <p>状态流转：</p>
 * <pre>
 * OPEN → IN_PROGRESS → RESOLVED / WONT_FIX
 * RESOLVED → REOPENED（缺口仍未消除）
 * </pre>
 */
@Data
@TableName("lg_gap_task")
public class GapTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    /** 缺口类型：doc_only_feature / code_only_feature / feature_without_entry 等 */
    private String gapType;

    /** 缺口标识：与 gapType 组成唯一约束 */
    private String gapKey;

    private String title;

    private String description;

    /** 严重度：LOW / MEDIUM / HIGH / CRITICAL */
    private String severity;

    /** 状态：OPEN / IN_PROGRESS / RESOLVED / WONT_FIX / REOPENED */
    private String status;

    /** 关联的主体类型（如 Feature） */
    private String subjectType;

    /** 关联的主体标识 */
    private String subjectKey;

    /** 关联 Claim ID 列表，JSONB */
    private String relatedClaimIds;

    /** 关联图谱节点 ID 列表，JSONB */
    private String relatedNodeIds;

    /** 关联证据 ID 列表，JSONB */
    private String evidenceIds;

    /** 补证建议（由 GapFinderAgent 生成） */
    private String suggestedAction;

    /** 关联的 AgentRun ID（记录生成该建议的 Agent 运行） */
    private Long agentRunId;

    /** 优先级评分 0~1 */
    private BigDecimal priorityScore;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
