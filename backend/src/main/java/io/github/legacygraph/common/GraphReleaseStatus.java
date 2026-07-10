package io.github.legacygraph.common;

/**
 * 图谱发布状态枚举。
 * <p>
 * 状态机流转：{@code DRAFT → VALIDATING → PUBLISHED | FAILED}
 * </p>
 */
public enum GraphReleaseStatus {

    DRAFT("草稿"),
    VALIDATING("校验中"),
    PUBLISHED("已发布"),
    FAILED("失败");

    private final String description;

    GraphReleaseStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
