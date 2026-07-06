package io.github.legacygraph.dto.systemoverview;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 系统关系总览事实底座导入请求。
 * <p>
 * 对应 {@code doc/系统关系总览/03-QA事实底座.md} 的结构化数据：
 * - {@link RelationRow} 对齐 Part B「业务能力映射总表」每行；
 * - {@link FaqCard} 对齐 Part A「人读 FAQ 卡片」每条。
 * </p>
 * <p>
 * 导入后写入三条通路：
 * 1. 向量（{@code lg_vector_document}，chunkType=SYSTEM_OVERVIEW）——供 HybridRetrieval 召回；
 * 2. Claim（{@code lg_knowledge_claim}）——供 GraphRagPlanExecutor 按 subjectKeys 检索；
 * 3. 语义缓存（{@code lg_semantic_cache}）——FAQ 相似问题直接命中。
 * </p>
 */
@Data
@Builder
public class SystemOverviewIngestRequest {

    /** 项目 ID（导入到哪个项目下；自身分析可填专属 ID 如 "self"） */
    private String projectId;

    /** 扫描版本 ID（为空时由服务层兜底为 "default"） */
    private String versionId;

    /** 结构化关系行（业务→功能→代码→数据 映射） */
    private List<RelationRow> relations;

    /** FAQ 卡片（question → answer + evidence） */
    private List<FaqCard> faqs;

    /**
     * 单行关系映射：业务域 → 业务能力 → 功能 → Controller/API → 代码 → 数据表。
     * 对齐 03 Part B1。
     */
    @Data
    @Builder
    public static class RelationRow {

        /** 业务域（如 "项目与数据源管理"） */
        private String businessDomain;

        /** 业务能力（如 "项目纳管"） */
        private String capability;

        /** 功能（前端页面，如 "project"） */
        private String feature;

        /** Controller 类名（如 "ProjectController"） */
        private String controller;

        /** API 基路径（如 "/lg/projects"） */
        private String apiPath;

        /** 代码模块（Service/Agent/Builder，如 "ProjectService"） */
        private String codeModule;

        /** 数据表（逗号分隔，如 "lg_project,lg_code_repo"） */
        private String dataTables;

        /** 主要边类型（如 "BELONGS_TO"） */
        private String edgeType;

        /** 来源类型：CODE（确定性映射，默认）/ DOC_AI（AI 抽取）。影响 Claim 状态 */
        private String sourceType;

        /** 置信度 0~1；为空时 CODE 默认 0.85，DOC_AI 默认 0.5 */
        private Double confidence;
    }

    /**
     * FAQ 卡片：question + answer + evidence(JSON)。
     * 对齐 03 Part A。导入后入语义缓存。
     */
    @Data
    @Builder
    public static class FaqCard {

        /** 问题 */
        private String question;

        /** 答案 */
        private String answer;

        /** 证据 JSON（可空） */
        private String evidence;
    }
}
