package io.github.legacygraph.common;

/**
 * 图谱关系类型
 */
public enum EdgeType {

    CONTAINS("包含"),
    IMPLEMENTED_BY("由...实现"),
    USES("使用"),
    HAS_RULE("拥有规则"),
    EXPOSED_BY("由...暴露"),
    REQUIRES_PERMISSION("需要权限"),
    CALLS("调用"),
    HANDLED_BY("由...处理"),
    EXECUTES("执行"),
    READS("读取"),
    WRITES("写入"),
    HAS_COLUMN("拥有字段"),
    JOINS("JOIN"),
    TRIGGERS("触发"),
    CONSUMES("消费"),
    CALLS_EXTERNAL("调用外部系统"),
    VERIFIED_BY("由...验证"),
    ASSERTS("断言"),
    HAS_EVIDENCE("拥有证据");

    private final String description;

    EdgeType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
