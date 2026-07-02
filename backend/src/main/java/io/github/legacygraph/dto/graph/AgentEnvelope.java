package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行信封 — 统一 Agent 调用的输入/证据/策略。
 * <p>
 * 每类 Agent 定义 RequiredEvidencePolicy，例如：
 * <ul>
 *   <li>PatchPlan 必须有 failing test 或 target evidence</li>
 *   <li>GraphMerge 必须有结构邻域与冲突证据</li>
 * </ul>
 * LlmGateway 在调用前校验证据策略，缺证据则返回 needsHumanReview。
 * </p>
 *
 * @param <T> 输入类型
 */
@Data
@Builder
public class AgentEnvelope<T> {

    /** 项目ID */
    private String projectId;

    /** 关联任务ID */
    private String taskId;

    /** Schema 版本 */
    @Builder.Default
    private String schemaVersion = "1.0";

    /** 输入数据 */
    private T input;

    /** 证据目录（必填证据类型及其ID列表） */
    private EvidenceCatalog evidenceCatalog;

    /** 证据策略（缺证据时的处理方式） */
    private RequiredEvidencePolicy policy;

    /** 合约ID（回放用） */
    private String contractId;

    /** Agent 类型 */
    private String agentType;

    /** 创建时间 */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 证据目录：声明本次调用需要使用哪些证据。
     */
    @Data
    @Builder
    public static class EvidenceCatalog {
        /** 使用的证据ID列表 */
        private List<String> usedEvidenceIds;

        /** 因隐私/权限原因被省略的证据ID */
        private List<String> omittedBecause;

        /** 必填证据类型（如 CODE_AST、TEST_RESULT） */
        private List<String> requiredEvidenceTypes;

        /** 证据摘要（供 prompt 使用） */
        private String summary;

        /** 额外元数据 */
        private Map<String, Object> metadata;

        public EvidenceCatalog() {}

        public EvidenceCatalog(List<String> usedEvidenceIds, List<String> omittedBecause,
                               List<String> requiredEvidenceTypes, String summary,
                               Map<String, Object> metadata) {
            this.usedEvidenceIds = usedEvidenceIds;
            this.omittedBecause = omittedBecause;
            this.requiredEvidenceTypes = requiredEvidenceTypes;
            this.summary = summary;
            this.metadata = metadata;
        }
    }

    /**
     * 必填证据策略 — 定义缺证据时的处理行为。
     */
    @Data
    @Builder
    public static class RequiredEvidencePolicy {
        /** 策略类型：REQUIRE / PREFER / OPTIONAL */
        @Builder.Default
        private String mode = "PREFER";

        /** 缺必要证据时是否返回 needsHumanReview */
        @Builder.Default
        private boolean failOnMissing = false;

        /** 最小置信度阈值 */
        @Builder.Default
        private double minConfidence = 0.5;

        /** 是否允许 AI 推断补充证据 */
        @Builder.Default
        private boolean allowAiInference = true;

        /** 策略描述 */
        private String description;

        // 工厂方法：严格模式（缺证据直接拒绝）
        public static RequiredEvidencePolicy strict() {
            return RequiredEvidencePolicy.builder()
                    .mode("REQUIRE")
                    .failOnMissing(true)
                    .allowAiInference(false)
                    .description("必填证据缺失时拒绝调用")
                    .build();
        }

        // 工厂方法：宽松模式（优先使用证据，允许 AI 补充）
        public static RequiredEvidencePolicy relaxed() {
            return RequiredEvidencePolicy.builder()
                    .mode("PREFER")
                    .failOnMissing(false)
                    .allowAiInference(true)
                    .description("优先使用证据，允许 AI 补充")
                    .build();
        }
    }

    public AgentEnvelope() {}

    public AgentEnvelope(String projectId, String taskId, String schemaVersion, T input,
                         EvidenceCatalog evidenceCatalog, RequiredEvidencePolicy policy,
                         String contractId, String agentType, LocalDateTime createdAt) {
        this.projectId = projectId;
        this.taskId = taskId;
        this.schemaVersion = schemaVersion;
        this.input = input;
        this.evidenceCatalog = evidenceCatalog;
        this.policy = policy;
        this.contractId = contractId;
        this.agentType = agentType;
        this.createdAt = createdAt;
    }
}
