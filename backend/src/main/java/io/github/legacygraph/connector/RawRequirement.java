package io.github.legacygraph.connector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 从外部需求源获取的原始需求数据（阶段四）。
 * <p>
 * 连接器从 Jira/Confluence/Linear 等系统获取需求后，封装为 RawRequirement，
 * 传递给需求建模服务转换为 {@link io.github.legacygraph.entity.Requirement}。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawRequirement {

    /** 外部系统中的唯一标识（如 Jira issue key） */
    private String externalId;

    /** 需求源类型 */
    private RequirementSource sourceType;

    /** 需求标题 */
    private String title;

    /** 需求正文 */
    private String description;

    /** 需求类型（story / bug / task / epic） */
    private String type;

    /** 优先级 */
    private String priority;

    /** 报告人 */
    private String reporter;

    /** 指派人 */
    private String assignee;

    /** 标签列表 */
    private List<String> labels;

    /** 外部系统 URL */
    private String sourceUrl;

    /** 创建时间（外部系统） */
    private LocalDateTime sourceCreatedAt;

    /** 更新时间（外部系统） */
    private LocalDateTime sourceUpdatedAt;
}
