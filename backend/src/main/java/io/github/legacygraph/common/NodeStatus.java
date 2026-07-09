package io.github.legacygraph.common;

/**
 * 图谱节点状态
 */
public enum NodeStatus {

    PENDING_CONFIRM("待确认"),
    CONFIRMED("已确认"),
    REJECTED("已驳回"),
    INVALID_CANDIDATE("无效候选"),
    DELETED("已删除"),
    STALE("待重扫");

    private final String description;

    NodeStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
