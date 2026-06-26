package io.github.legacygraph.common;

/**
 * 任务状态
 */
public enum TaskStatus {

    CREATED("已创建"),
    PENDING("等待中"),
    RUNNING("运行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    CANCELLED("已取消"),
    SKIPPED("已跳过");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
