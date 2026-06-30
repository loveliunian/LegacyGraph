package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱场景 DSL — 用于驱动测试生成和验证。
 * <p>
 * 将 FeatureSlice 的路径信息转换为可执行的测试场景描述。
 * 测试执行器消费此 DSL，AI 补全自然语言步骤和边界场景。
 * </p>
 *
 * <pre>
 * scenario_id: feature.order.create.happy_path
 * slice:
 *   feature: 订单创建
 *   entry: /orders/new
 *   api: POST /api/orders
 * actors:
 *   role: sales
 * preconditions:
 *   - customer exists
 * actions:
 *   - ui.fill form
 *   - ui.click submit
 * assertions:
 *   - http.status == 201
 *   - db.orders.status == CREATED
 *   - graph.path_observed == true
 * </pre>
 */
@Data
@Builder
public class ScenarioDSL {

    /** 场景唯一标识 */
    private String scenarioId;

    /** 关联的 FeatureSlice ID */
    private String sliceId;

    /** 场景名称 */
    private String name;

    /** 场景描述 */
    private String description;

    /** 场景类型 (API / DB / E2E) */
    private String scenarioType;

    /** 入口路径 */
    private String entryPath;

    /** HTTP 方法 */
    private String httpMethod;

    /** API 路径 */
    private String apiPath;

    /** 请求体模板 */
    private String requestBody;

    // ========== 演员 ==========

    /** 角色 */
    private String role;

    // ========== 前置条件 ==========

    /** 前置条件列表 */
    private List<String> preconditions;

    // ========== 动作列表 ==========

    private List<String> actions;

    // ========== 断言 ==========

    /** 断言列表 */
    private List<Assertion> assertions;

    // ========== 路径节点 ==========

    /** 路径上的 API 节点ID */
    private List<String> apiIds;

    /** 路径上的 SQL 节点ID */
    private List<String> sqlIds;

    /** 路径上的表节点ID */
    private List<String> tableIds;

    /**
     * 单条断言。
     */
    @Data
    @Builder
    public static class Assertion {
        private String type;     // http_status / db / graph / response
        private String field;
        private String operator;  // == / != / contains / exists / >
        private String expectedValue;
        private String description;

        public Assertion() {}

        public Assertion(String type, String field, String operator,
                         String expectedValue, String description) {
            this.type = type;
            this.field = field;
            this.operator = operator;
            this.expectedValue = expectedValue;
            this.description = description;
        }
    }

    public ScenarioDSL() {
        this.preconditions = new ArrayList<>();
        this.actions = new ArrayList<>();
        this.assertions = new ArrayList<>();
        this.apiIds = new ArrayList<>();
        this.sqlIds = new ArrayList<>();
        this.tableIds = new ArrayList<>();
    }

    public ScenarioDSL(String scenarioId, String sliceId, String name,
                       String description, String scenarioType, String entryPath,
                       String httpMethod, String apiPath, String requestBody,
                       String role, List<String> preconditions,
                       List<String> actions, List<Assertion> assertions,
                       List<String> apiIds, List<String> sqlIds,
                       List<String> tableIds) {
        this.scenarioId = scenarioId;
        this.sliceId = sliceId;
        this.name = name;
        this.description = description;
        this.scenarioType = scenarioType;
        this.entryPath = entryPath;
        this.httpMethod = httpMethod;
        this.apiPath = apiPath;
        this.requestBody = requestBody;
        this.role = role;
        this.preconditions = preconditions != null ? preconditions : new ArrayList<>();
        this.actions = actions != null ? actions : new ArrayList<>();
        this.assertions = assertions != null ? assertions : new ArrayList<>();
        this.apiIds = apiIds != null ? apiIds : new ArrayList<>();
        this.sqlIds = sqlIds != null ? sqlIds : new ArrayList<>();
        this.tableIds = tableIds != null ? tableIds : new ArrayList<>();
    }
}
