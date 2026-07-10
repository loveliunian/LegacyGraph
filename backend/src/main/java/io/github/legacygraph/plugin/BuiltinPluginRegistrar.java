package io.github.legacygraph.plugin;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginType;

/**
 * 内置插件自动注册器（P3-1）
 * 应用启动时自动注册所有内置 Scanner/Agent/Tool/GraphView 插件。
 */
@Component
public class BuiltinPluginRegistrar {

    private final PluginRegistry registry;

    public BuiltinPluginRegistrar(PluginRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerBuiltinPlugins() {
        // Scanner 插件
        registry.register(new PluginDescriptor("java-scanner", "Java 代码扫描器", "基于 JavaParser 的 AST 分析", PluginType.SCANNER));
        registry.register(new PluginDescriptor("vue-scanner", "Vue 前端扫描器", "Vue3 SFC 组件/路由分析", PluginType.SCANNER));
        registry.register(new PluginDescriptor("sql-scanner", "SQL 扫描器", "MyBatis XML + 注解 SQL 提取", PluginType.SCANNER));
        registry.register(new PluginDescriptor("doc-scanner", "文档扫描器", "Markdown/OpenAPI 文档解析", PluginType.SCANNER));

        // Agent 插件
        registry.register(new PluginDescriptor("sql-agent", "SQL 分析 Agent", "SQL 风险与优化建议", PluginType.AGENT));
        registry.register(new PluginDescriptor("test-agent", "测试生成 Agent", "基于功能描述生成测试用例", PluginType.AGENT));
        registry.register(new PluginDescriptor("review-agent", "审核建议 Agent", "代码/知识主张审核评分", PluginType.AGENT));
        registry.register(new PluginDescriptor("refactor-agent", "重构建议 Agent", "代码异味检测与拆分建议", PluginType.AGENT));
        registry.register(new PluginDescriptor("migration-agent", "迁移转换 Agent", "跨框架/版本迁移代码转换", PluginType.AGENT));
        registry.register(new PluginDescriptor("pr-agent", "PR 描述 Agent", "自动生成 PR 描述与提交信息", PluginType.AGENT));
        registry.register(new PluginDescriptor("failure-agent", "失败分析 Agent", "测试失败根因分析", PluginType.AGENT));
        registry.register(new PluginDescriptor("change-agent", "变更影响 Agent", "变更影响范围分析", PluginType.AGENT));
        registry.register(new PluginDescriptor("report-agent", "报告洞察 Agent", "扫描报告指标解读与行动建议", PluginType.AGENT));

        // Tool 插件
        registry.register(new PluginDescriptor("neo4j-tool", "Neo4j 图查询", "Cypher 查询与图操作", PluginType.TOOL));
        registry.register(new PluginDescriptor("vector-tool", "向量检索", "pgvector 语义搜索", PluginType.TOOL));
        registry.register(new PluginDescriptor("codegraph-tool", "CodeGraph MCP", "MCP 协议代码图谱查询", PluginType.TOOL));

        // GraphView 插件
        registry.register(new PluginDescriptor("code-graph-view", "代码图谱视图", "类/方法/调用关系可视化", PluginType.GRAPH_VIEW));
        registry.register(new PluginDescriptor("business-graph-view", "业务图谱视图", "业务实体与流程可视化", PluginType.GRAPH_VIEW));
        registry.register(new PluginDescriptor("unified-graph-view", "统一图谱视图", "多图层融合展示", PluginType.GRAPH_VIEW));
        registry.register(new PluginDescriptor("lineage-graph-view", "数据血缘视图", "数据流向与依赖关系", PluginType.GRAPH_VIEW));
        // System Overview 插件（04 阶段2）
        registry.register(new PluginDescriptor("system-overview-scanner", "系统关系总览扫描器", "业务/功能/代码/数据四层关系投影", PluginType.SCANNER));
        registry.register(new PluginDescriptor("system-overview-view", "系统关系总览视图", "四层关系可视化与链路下钻", PluginType.GRAPH_VIEW));

        // Graphify 整体作为一个可插拔插件，子功能存入 metadata.menuItems（JSON）
        // 启用/禁用统一控制，4 个子功能一起注入到"图谱管理"菜单组
        registry.register(new PluginDescriptor(
                "graphify", "Graphify 融合", "图谱导入、版本差异、质量评估与跨仓影响分析",
                PluginType.GRAPH_VIEW, "graph", true,
                java.util.Map.of("menuItems", """
                    [
                        {"menuLabel":"Graphify 作业中心","menuPath":"graphify/jobs","routeName":"GraphifyJobs"},
                        {"menuLabel":"Graphify 版本差异","menuPath":"graphify/diff","routeName":"GraphifyDiff"},
                        {"menuLabel":"Graphify 质量仪表盘","menuPath":"graphify/quality","routeName":"GraphifyQuality"},
                        {"menuLabel":"跨仓影响分析","menuPath":"graphify/cross-repo-impact","routeName":"GraphifyCrossRepoImpact"}
                    ]
                """)));
    }
}
