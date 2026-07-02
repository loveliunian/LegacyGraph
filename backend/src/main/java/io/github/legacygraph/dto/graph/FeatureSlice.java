package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 功能切片 — 业务图谱和代码图谱之间的桥。
 * <p>
 * 一条完整的用户路径从业务功能贯穿到数据库表：
 * </p>
 * <pre>
 * BusinessProcess / Feature
 *   → Page / Button / Action
 *   → ApiEndpoint
 *   → Method / Service / Mapper
 *   → SqlStatement
 *   → Table / Column
 *   → Permission / BusinessRule
 *   → TestScenario / Assertion
 * </pre>
 *
 * <p>FeatureSlice 不是新建第三套图，而是总图上的投影视图。</p>
 *
 * <p>增强版（M5）：支持多入口动态切片，
 * 入口类型包括 Page/ApiEndpoint/ScheduledJob/MessageConsumer/BatchTask/ExternalCallback。</p>
 */
@Data
@Builder
public class FeatureSlice {

    /** 切片唯一标识 */
    private String sliceId;

    /** 项目ID */
    private String projectId;

    /** 版本ID */
    private String versionId;

    /** 切片名称（由 LLM 或业务规则命名） */
    private String name;

    /** 入口：业务功能名称 */
    private String featureName;

    /** 入口：页面路径 */
    private String entryPage;

    // ========== 关键路径节点（传统固定层级，保留向后兼容） ==========

    /** 路径上的页面节点ID列表 */
    private List<String> pageIds;

    /** 路径上的 API 节点ID列表 */
    private List<String> apiIds;

    /** 路径上的方法节点ID列表 */
    private List<String> methodIds;

    /** 路径上的 SQL 节点ID列表 */
    private List<String> sqlIds;

    /** 路径上的表节点ID列表 */
    private List<String> tableIds;

    /** 路径上的权限节点ID列表 */
    private List<String> permissionIds;

    /** 关联的业务规则ID列表 */
    private List<String> ruleIds;

    /** 关联的测试场景ID列表 */
    private List<String> testCaseIds;

    // ========== M5 动态多入口切片字段 ==========

    /**
     * 入口节点列表。
     * 支持多种入口类型：Page（页面）、ApiEndpoint（API接口）、ScheduledJob（定时任务）、
     * MessageConsumer（消息消费者）、BatchTask（批处理任务）、ExternalCallback（外部回调）。
     */
    private List<SliceNodeRef> entrances;

    /** 实现节点列表：Controller/Service/Method/Mapper 等 */
    private List<SliceNodeRef> implementation;

    /** 数据节点列表：SqlStatement/Table/Column 等 */
    private List<SliceNodeRef> data;

    /** 规则节点列表：BusinessRule/Permission 等 */
    private List<SliceNodeRef> rules;

    /** 验证节点列表：TestCase/Assertion 等 */
    private List<SliceNodeRef> verification;

    /** 缺口节点列表：缺失的入口/实现/数据/规则/验证 */
    private List<SliceNodeRef> gaps;

    // ========== 状态信息 ==========

    /** 切片的置信度 */
    private BigDecimal confidence;

    /** 切片状态 */
    private String status;

    /** 风险等级 (LOW/MEDIUM/HIGH) */
    private String riskLevel;

    /** 覆盖状态 (COVERED/PARTIAL/UNCOVERED) */
    private String coverageStatus;

    /** 证据来源类型列表 */
    private List<String> evidenceSources;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    // ========== 构造函数 ==========

    public FeatureSlice() {
        this.pageIds = new ArrayList<>();
        this.apiIds = new ArrayList<>();
        this.methodIds = new ArrayList<>();
        this.sqlIds = new ArrayList<>();
        this.tableIds = new ArrayList<>();
        this.permissionIds = new ArrayList<>();
        this.ruleIds = new ArrayList<>();
        this.testCaseIds = new ArrayList<>();
        this.evidenceSources = new ArrayList<>();
        this.entrances = new ArrayList<>();
        this.implementation = new ArrayList<>();
        this.data = new ArrayList<>();
        this.rules = new ArrayList<>();
        this.verification = new ArrayList<>();
        this.gaps = new ArrayList<>();
    }

    public FeatureSlice(String sliceId, String projectId, String versionId,
                        String name, String featureName, String entryPage,
                        List<String> pageIds, List<String> apiIds,
                        List<String> methodIds, List<String> sqlIds,
                        List<String> tableIds, List<String> permissionIds,
                        List<String> ruleIds, List<String> testCaseIds,
                        List<SliceNodeRef> entrances, List<SliceNodeRef> implementation,
                        List<SliceNodeRef> data, List<SliceNodeRef> rules,
                        List<SliceNodeRef> verification, List<SliceNodeRef> gaps,
                        BigDecimal confidence, String status, String riskLevel,
                        String coverageStatus, List<String> evidenceSources,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.sliceId = sliceId;
        this.projectId = projectId;
        this.versionId = versionId;
        this.name = name;
        this.featureName = featureName;
        this.entryPage = entryPage;
        this.pageIds = pageIds != null ? pageIds : new ArrayList<>();
        this.apiIds = apiIds != null ? apiIds : new ArrayList<>();
        this.methodIds = methodIds != null ? methodIds : new ArrayList<>();
        this.sqlIds = sqlIds != null ? sqlIds : new ArrayList<>();
        this.tableIds = tableIds != null ? tableIds : new ArrayList<>();
        this.permissionIds = permissionIds != null ? permissionIds : new ArrayList<>();
        this.ruleIds = ruleIds != null ? ruleIds : new ArrayList<>();
        this.testCaseIds = testCaseIds != null ? testCaseIds : new ArrayList<>();
        this.entrances = entrances != null ? entrances : new ArrayList<>();
        this.implementation = implementation != null ? implementation : new ArrayList<>();
        this.data = data != null ? data : new ArrayList<>();
        this.rules = rules != null ? rules : new ArrayList<>();
        this.verification = verification != null ? verification : new ArrayList<>();
        this.gaps = gaps != null ? gaps : new ArrayList<>();
        this.confidence = confidence;
        this.status = status;
        this.riskLevel = riskLevel;
        this.coverageStatus = coverageStatus;
        this.evidenceSources = evidenceSources != null ? evidenceSources : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ========== 内部类：切片节点引用 ==========

    /**
     * 切片节点引用 — 轻量级节点描述符，用于 FeatureSlice 的各个分组列表。
     * <p>
     * 包含节点基本信息，避免每次引用都需要查询完整 GraphNode。
     * </p>
     */
    @Data
    @Builder
    public static class SliceNodeRef {
        /** 节点ID */
        private String nodeId;

        /** 节点类型（NodeType 名称） */
        private String nodeType;

        /** 节点名称 */
        private String nodeName;

        /** 显示名称 */
        private String displayName;

        /** 描述 */
        private String description;

        /** 来源类型 */
        private String sourceType;

        /** 置信度 */
        private BigDecimal confidence;

        /** 来源边类型（该节点是通过什么边连接到 Feature 的） */
        private String edgeType;

        public SliceNodeRef() {
        }

        public SliceNodeRef(String nodeId, String nodeType, String nodeName,
                            String displayName, String description, String sourceType,
                            BigDecimal confidence, String edgeType) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.nodeName = nodeName;
            this.displayName = displayName;
            this.description = description;
            this.sourceType = sourceType;
            this.confidence = confidence;
            this.edgeType = edgeType;
        }
    }
}
