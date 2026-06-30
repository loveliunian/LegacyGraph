package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行合约 — 使 AI 输出可控、可回放、可评估。
 * <p>
 * 每次 Agent 调用都产生一条 AgentRunContract，记录：
 * </p>
 * <ul>
 *   <li>输入 schema 与输出 schema 版本化</li>
 *   <li>必填证据类型和禁止无证据输出的字段</li>
 *   <li>结构化解析失败时自我修复记录</li>
 *   <li>usedEvidenceIds、omittedBecause、needsHumanReview</li>
 *   <li>成本预算、模型路由、重试次数、质量评估分</li>
 * </ul>
 */
@Data
@Builder
public class AgentRunContract {

    /** 合约唯一标识 */
    private String contractId;

    /** 项目ID */
    private String projectId;

    /** Agent 类型 */
    private String agentType;

    /** Agent 名称 */
    private String agentName;

    /** 输入 schema 版本 */
    private String inputSchemaVersion;

    /** 输出 schema 版本 */
    private String outputSchemaVersion;

    /** 使用的证据ID列表 */
    private List<String> usedEvidenceIds;

    /** 因隐私/权限原因被省略的证据 */
    private List<String> omittedBecause;

    /** 是否需要人工审核 */
    private boolean needsHumanReview;

    /** 使用的模型 */
    private String model;

    /** Token 消耗 */
    private Integer promptTokens;

    /** Token 消耗 */
    private Integer completionTokens;

    /** 总成本（USD） */
    private Double costUsd;

    /** 重试次数 */
    private int retryCount;

    /** 自我修复次数 */
    private int selfCorrectionCount;

    /** 质量评估分 (0-1) */
    private Double qualityScore;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 额外元数据 */
    private Map<String, Object> metadata;

    public AgentRunContract() {}

    public AgentRunContract(String contractId, String projectId, String agentType,
                            String agentName, String inputSchemaVersion,
                            String outputSchemaVersion, List<String> usedEvidenceIds,
                            List<String> omittedBecause, boolean needsHumanReview,
                            String model, Integer promptTokens, Integer completionTokens,
                            Double costUsd, int retryCount, int selfCorrectionCount,
                            Double qualityScore, LocalDateTime startedAt,
                            LocalDateTime finishedAt, Map<String, Object> metadata) {
        this.contractId = contractId;
        this.projectId = projectId;
        this.agentType = agentType;
        this.agentName = agentName;
        this.inputSchemaVersion = inputSchemaVersion;
        this.outputSchemaVersion = outputSchemaVersion;
        this.usedEvidenceIds = usedEvidenceIds;
        this.omittedBecause = omittedBecause;
        this.needsHumanReview = needsHumanReview;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costUsd = costUsd;
        this.retryCount = retryCount;
        this.selfCorrectionCount = selfCorrectionCount;
        this.qualityScore = qualityScore;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.metadata = metadata;
    }
}
