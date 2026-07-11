package io.github.legacygraph.common;

/**
 * 图谱关系类型
 */
public enum EdgeType {

    CONTAINS("包含"),
    IMPLEMENTED_BY("由...实现"),
    IMPLEMENTS("实现"),
    EXTENDS("继承"),
    USES("使用"),
    HAS_RULE("拥有规则"),
    EXPOSED_BY("由...暴露"),
    REQUIRES_PERMISSION("需要权限"),
    REQUIRES("需要"),
    CALLS("调用"),
    HANDLED_BY("由...处理"),
    EXECUTES("执行"),
    READS("读取"),
    WRITES("写入"),
    HAS_COLUMN("拥有字段"),
    HAS_INDEX("拥有索引"),
    UNIQUE_ON("唯一约束字段"),
    JOINS("JOIN"),
    TRIGGERS("触发"),
    CONSUMES("消费"),
    CALLS_EXTERNAL("调用外部系统"),
    VERIFIED_BY("由...验证"),
    ASSERTS("断言"),
    HAS_EVIDENCE("拥有证据"),
    REFERENCES("外键引用"),
    BELONGS_TO("属于"),
    MAPS_TO("对应"),
    POSSIBLE_SAME_AS("疑似同义"),
    APPLIES_TO("应用于"),
    GRANTS("授予"),        // Role --GRANTS--> Permission：角色被授予某权限
    ASSIGNED_TO("分配给"),  // Role --ASSIGNED_TO--> User：角色被分配给某用户
    DATA_FLOW("数据流"),
    REQUIRES_DOCUMENT("需要文档"),

    // ========== 业务关键边（P1-2 补充） ==========
    CALLS_DB("调用数据库"),
    READS_DB("读取数据库"),
    WRITES_DB("写入数据库"),
    WRITES_LOG("写日志"),
    READS_CONFIG("读配置"),
    WRITES_CONFIG("写配置"),
    EXPOSES_ENDPOINT("暴露接口"),
    AUTHENTICATES_BY("认证方式"),
    AUTHORIZES_BY("授权方式"),

    // ========== 变更闭环关系（增强版2：ChangeTask 管道） ==========
    AFFECTS("影响"),
    FIXED_BY("由...修复"),
    MIGRATES_TO("迁移到"),
    DEPENDS_ON("依赖于"),

    // ========== 需求结构化关系（Task 6） ==========
    HAS_ITEM("拥有条目"),                              // Requirement → RequirementItem
    HAS_ACCEPTANCE_CRITERION("拥有验收条件"),          // RequirementItem → AcceptanceCriterion
    HAS_CONSTRAINT("拥有约束"),                        // RequirementItem → Constraint
    HAS_ASSUMPTION("拥有假设"),                        // RequirementItem → Assumption
    RAISES_QUESTION("提出问题"),                      // RequirementItem → OpenQuestion
    SATISFIES("满足"),                                 // Solution → Requirement（Solution 在 Task 10 定义）
    DERIVED_FROM("派生自"),                            // ImplementationStep → Requirement
    VERIFIES("验证"),                                  // VerificationStep → AcceptanceCriterion

    // ========== 方案生成关系（Task 10） ==========
    STEP_OF("步骤属于"),                               // SolutionStep → Solution
    VALIDATED_BY("由...校验"),                         // Solution / SolutionStep → Evidence / ValidationGate
    REVISED_BY("由...修订");                           // Solution → Solution（修订版本链）

    private final String description;

    EdgeType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
