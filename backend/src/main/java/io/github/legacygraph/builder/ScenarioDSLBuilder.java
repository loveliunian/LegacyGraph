package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.dto.graph.ScenarioDSL;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景 DSL 构建器（见 doc 4.2）— 把 {@link FeatureSlice} 的路径固化为可执行场景。
 * <p>
 * 一个切片在每一层都会扇出（多 API / 多表），因此一个切片产出多个 ScenarioDSL：
 * 每个 API 节点生成一个 API 场景；若无 API 但有表，则生成 DB 场景。
 * 测试执行器消费 TestCase（由 DSL 映射而来），AI 仅补全自然语言步骤与边界场景。
 * </p>
 */
@Slf4j
@Component
public class ScenarioDSLBuilder {

    private final Neo4jGraphDao neo4jGraphDao;

    public ScenarioDSLBuilder(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 从功能切片生成场景 DSL 列表。
     */
    public List<ScenarioDSL> buildFromSlice(FeatureSlice slice) {
        List<ScenarioDSL> scenarios = new ArrayList<>();
        if (slice == null || slice.getSliceId() == null) {
            return scenarios;
        }

        List<String> apiIds = slice.getApiIds() != null ? slice.getApiIds() : List.of();
        List<String> tableIds = slice.getTableIds() != null ? slice.getTableIds() : List.of();

        // 每个 API 节点生成一个 API 场景（含路径上的 DB 断言）
        for (String apiId : apiIds) {
            GraphNode apiNode = neo4jGraphDao.findNodeById(apiId).orElse(null);
            if (apiNode == null) continue;
            ScenarioDSL dsl = buildApiScenario(slice, apiNode, tableIds);
            if (dsl != null) {
                scenarios.add(dsl);
            }
        }

        // 无 API 但有表：生成 DB 场景
        if (scenarios.isEmpty() && !tableIds.isEmpty()) {
            for (String tableId : tableIds) {
                GraphNode tableNode = neo4jGraphDao.findNodeById(tableId).orElse(null);
                if (tableNode == null) continue;
                scenarios.add(buildDbScenario(slice, tableNode));
            }
        }

        log.info("Built {} scenario DSL(s) from slice {}", scenarios.size(), slice.getSliceId());
        return scenarios;
    }

    private ScenarioDSL buildApiScenario(FeatureSlice slice, GraphNode apiNode, List<String> tableIds) {
        String[] parts = parseMethodPath(apiNode);
        String method = parts[0];
        String path = parts[1];

        List<ScenarioDSL.Assertion> assertions = new ArrayList<>();
        assertions.add(ScenarioDSL.Assertion.builder()
                .type("http_status").operator("==").expectedValue("200")
                .description("接口返回 200").build());
        assertions.add(ScenarioDSL.Assertion.builder()
                .type("graph").field("path_observed").operator("==").expectedValue("true")
                .description("图谱路径被运行时观测").build());
        for (String tableId : tableIds) {
            GraphNode table = neo4jGraphDao.findNodeById(tableId).orElse(null);
            String tableName = table != null ? table.getNodeName() : tableId;
            assertions.add(ScenarioDSL.Assertion.builder()
                    .type("db").field(tableName + ".rows").operator(">=").expectedValue("0")
                    .description("表 " + tableName + " 行数校验").build());
        }

        List<String> actions = new ArrayList<>();
        actions.add("ui.open " + (slice.getEntryPage() != null ? slice.getEntryPage() : "/"));
        actions.add("api.call " + method + " " + path);

        return ScenarioDSL.builder()
                .scenarioId(slice.getSliceId() + ":" + apiNode.getId())
                .sliceId(slice.getSliceId())
                .name(slice.getName() + " - " + (apiNode.getDisplayName() != null
                        ? apiNode.getDisplayName() : apiNode.getNodeName()))
                .description("由 Feature Slice 生成的 API 场景")
                .scenarioType("API")
                .entryPath(slice.getEntryPage())
                .httpMethod(method)
                .apiPath(path)
                .role("user")
                .preconditions(List.of("用户已登录", "业务前置数据就绪"))
                .actions(actions)
                .assertions(assertions)
                .apiIds(List.of(apiNode.getId()))
                .sqlIds(slice.getSqlIds())
                .tableIds(tableIds)
                .build();
    }

    private ScenarioDSL buildDbScenario(FeatureSlice slice, GraphNode tableNode) {
        List<ScenarioDSL.Assertion> assertions = new ArrayList<>();
        assertions.add(ScenarioDSL.Assertion.builder()
                .type("db").field(tableNode.getNodeName() + ".rows").operator(">").expectedValue("0")
                .description("表 " + tableNode.getNodeName() + " 非空").build());

        return ScenarioDSL.builder()
                .scenarioId(slice.getSliceId() + ":db:" + tableNode.getId())
                .sliceId(slice.getSliceId())
                .name(slice.getName() + " - DB " + tableNode.getNodeName())
                .description("由 Feature Slice 生成的 DB 断言场景")
                .scenarioType("DB")
                .role("user")
                .preconditions(List.of("数据库可访问"))
                .actions(List.of("db.assert " + tableNode.getNodeName()))
                .assertions(assertions)
                .tableIds(List.of(tableNode.getId()))
                .build();
    }

    private String[] parseMethodPath(GraphNode apiNode) {
        String key = apiNode.getNodeKey();
        if (key != null && key.contains(" ")) {
            String[] parts = key.split(" ", 2);
            return new String[]{parts[0], parts[1]};
        }
        return new String[]{"GET", key != null ? key : "/"};
    }
}
