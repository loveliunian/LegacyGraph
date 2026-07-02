package io.github.legacygraph.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GraphRAG 查询计划 DTO。
 * <p>
 * PlannerAgent 根据用户问题与相关 KnowledgeClaim 生成多步查询计划，
 * 包含子问题拆分、Claim 过滤条件、Neo4j 路径模板以及所需证据类型。
 * 计划由 LLM 以 JSON 形式输出，经 {@code callWithEnvelope} 反序列化为此对象。
 * </p>
 *
 * <p>典型使用场景：</p>
 * <ul>
 *   <li>用户提问 "订单创建涉及哪些表和 API？"</li>
 *   <li>PlannerAgent 从 Feature/API/Table 等相关 Claim 中规划子问题</li>
 *   <li>逐层查询 Neo4j 图谱，聚合多步结果</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagPlan {

    /** 原始用户问题 */
    private String question;

    /**
     * 子问题列表 — 将复杂问题拆分为多个可独立查询的子问题。
     * 每个子问题明确 targetType 和具体查询文本，便于路由到对应查询执行器。
     */
    private List<SubQuestion> subQuestions;

    /**
     * Claim 过滤条件 — 用于从知识断言库中筛选相关 Claim。
     * 支持按 subjectType / predicate / sourceType / confidence 等维度过滤。
     */
    private List<ClaimQuery> claimQueries;

    /**
     * Neo4j 路径查询模板 — 指定图遍历的起点、关系和终点类型。
     * 用于在知识图谱中执行结构化路径匹配查询。
     */
    private List<PathQuery> pathQueries;

    /**
     * 所需证据类型列表。
     * 例如：CODE_AST、TABLE_SCHEMA、API_DOC、TEST_RESULT、RUNTIME_TRACE 等。
     * 用于告知下游 Agent 需收集哪些证据来支撑查询结论。
     */
    private List<String> requiredEvidenceTypes;

    // ==================== 可选元信息 ====================

    /** 计划整体推理说明（LLM 生成的自然语言解释） */
    private String reasoning;

    /** 是否需要人工复核此计划 */
    @Builder.Default
    private boolean needsHumanReview = false;

    // ==================== 内嵌 DTO ====================

    /**
     * 子问题 — 将复杂问题拆分后的独立查询单元。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubQuestion {
        /**
         * 目标类型：决定子问题路由到哪种查询执行器。
         * 取值示例：Feature / API / Table / BusinessRule / Code / Doc / Runtime
         */
        private String targetType;

        /** 子问题查询文本 */
        private String query;

        /**
         * 优先级：决定查询执行顺序。
         * HIGH → 必须先执行，后续子问题可能依赖其结果
         * NORMAL → 默认顺序
         * LOW → 可延迟或在有空闲时执行
         */
        @Builder.Default
        private String priority = "NORMAL";

        /** 该子问题依赖的父问题索引（subQuestions 列表下标，-1 表示无依赖） */
        @Builder.Default
        private int dependsOn = -1;

    }

    /**
     * Claim 查询条件 — 用于筛选知识断言库中的相关 Claim。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaimQuery {
        /**
         * 主体类型过滤。
         * 取值示例：Feature / ApiEndpoint / Method / BusinessObject / BusinessRule
         */
        private String subjectType;

        /** 主体标识模糊匹配 */
        private String subjectKey;

        /**
         * 谓词过滤。
         * 取值示例：HANDLED_BY / CALLS / READS / WRITES / EXPOSED_BY / IMPLEMENTS / MATERIALIZED_AS
         */
        private String predicate;

        /** 客体类型过滤 */
        private String objectType;

        /** 客体标识模糊匹配 */
        private String objectKey;

        /** 来源类型过滤（CODE / DOC / DB / AI / TEST / RUNTIME） */
        private String sourceType;

        /** 最低置信度阈值（0.0 ~ 1.0） */
        @Builder.Default
        private double minConfidence = 0.5;

        /** 查询目的说明 */
        private String purpose;

    }

    /**
     * Neo4j 路径查询模板 — 在图谱中执行结构化路径遍历。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathQuery {
        /**
         * 起始节点类型/标签。
         * 例如：Feature / API / Table / Method / BusinessObject
         */
        private String startNodeType;

        /** 起始节点属性过滤（如 {name: "订单创建", projectId: "xxx"}） */
        private String startNodeFilter;

        /**
         * 关系类型/方向。
         * 例如：[:HANDLED_BY]-> / <-[:READS]- / -[:MATERIALIZED_AS]->
         */
        private String relationshipPattern;

        /**
         * 目标节点类型/标签。
         * 例如：API / Table / Column / BusinessRule
         */
        private String endNodeType;

        /** 目标节点属性过滤 */
        private String endNodeFilter;

        /**
         * 路径深度限制。
         * 例如：1..3 表示 1 到 3 跳。
         */
        @Builder.Default
        private String pathDepth = "1..3";

        /** 查询目的说明 */
        private String purpose;

    }
}
