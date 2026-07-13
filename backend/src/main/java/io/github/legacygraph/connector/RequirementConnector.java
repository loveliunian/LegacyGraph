package io.github.legacygraph.connector;

import java.util.List;

/**
 * 需求源连接器接口（阶段四）。
 * <p>
 * 实现此接口并注册为 Spring Bean，即可在系统中启用对应需求源。
 * 支持的需求源：Jira / Confluence / Linear / GitHub Issues / 手工粘贴 / 文档导入。
 * </p>
 * <p>
 * 典型实现流程：
 * <ol>
 *   <li>{@link #fetchRequirements}：从外部系统拉取需求，返回 RawRequirement 列表</li>
 *   <li>需求建模服务将 RawRequirement 转换为内部 Requirement 实体</li>
 *   <li>{@link #syncBack}：将内部需求状态同步回外部系统</li>
 * </ol>
 * </p>
 */
public interface RequirementConnector {

    /**
     * 连接器唯一标识。
     *
     * @return 连接器 ID（如 "jira" / "confluence" / "linear"）
     */
    String getId();

    /**
     * 连接器名称（中文）。
     *
     * @return 连接器名称
     */
    String getName();

    /**
     * 支持的需求源类型。
     *
     * @return 需求源类型枚举
     */
    RequirementSource getSourceType();

    /**
     * 同步需求（定时或手动触发）。
     * <p>
     * 从外部系统拉取需求，封装为 RawRequirement 列表返回。
     * 调用方负责将 RawRequirement 转换为内部 Requirement 实体。
     * </p>
     *
     * @param ctx 同步上下文（含项目 ID、过滤条件、配置等）
     * @return 原始需求列表
     */
    List<RawRequirement> fetchRequirements(SyncContext ctx);

    /**
     * 将需求状态同步回源系统。
     * <p>
     * 当内部需求状态变更（如方案已生成、PR 已创建）时，回调此方法
     * 将状态同步回外部系统（如更新 Jira issue 状态）。
     * </p>
     *
     * @param requirementId 内部需求 ID
     * @param result        同步结果信息
     */
    void syncBack(String requirementId, SyncResult result);
}
