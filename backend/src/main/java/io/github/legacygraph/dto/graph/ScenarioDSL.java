package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@NoArgsConstructor
@AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assertion {
        private String type;     // http_status / db / graph / response
        private String field;
        private String operator;  // == / != / contains / exists / >
        private String expectedValue;
        private String description;

    }

}
