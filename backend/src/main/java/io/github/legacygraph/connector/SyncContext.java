package io.github.legacygraph.connector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 需求同步上下文（阶段四）。
 * <p>
 * 传递给连接器的同步参数，包含项目信息、同步范围、过滤条件等。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncContext {

    /** 项目 ID */
    private String projectId;

    /** 同步起始时间（增量同步用） */
    private LocalDateTime since;

    /** 最大同步数量 */
    private int maxResults;

    /** 过滤条件（如 Jira JQL、GitHub Issues filter） */
    private String filter;

    /** 额外配置（如 API token、project key 等） */
    private Map<String, String> config;
}
