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

    // ========== 关键路径节点 ==========

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
    }

    public FeatureSlice(String sliceId, String projectId, String versionId,
                        String name, String featureName, String entryPage,
                        List<String> pageIds, List<String> apiIds,
                        List<String> methodIds, List<String> sqlIds,
                        List<String> tableIds, List<String> permissionIds,
                        List<String> ruleIds, List<String> testCaseIds,
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
        this.confidence = confidence;
        this.status = status;
        this.riskLevel = riskLevel;
        this.coverageStatus = coverageStatus;
        this.evidenceSources = evidenceSources != null ? evidenceSources : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
