package io.github.legacygraph.common;

/**
 * 事实来源类型
 */
public enum SourceType {

    CODE_AST("代码AST解析"),
    MYBATIS_XML("MyBatis XML解析"),
    SQL_PARSE("SQL解析"),
    DB_METADATA("数据库元数据"),
    FRONTEND_AST("前端AST解析"),
    GRAPHIFY_AST("Graphify AST抽取"),
    GRAPHIFY_SEMANTIC("Graphify语义抽取"),
    DOC_AI("文档AI抽取"),
    CODE_AI("代码AI抽取"),
    CONFIG_FILE("配置文件"),
    AI_INFERENCE("AI推断"),
    TEST_EXECUTION("测试执行"),
    RUNTIME_TRACE("运行时链路"),
    MANUAL_CONFIRM("人工确认");

    private final String description;

    SourceType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
