package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具运行记录实体 —— 记录每次外部工具调用的元数据。
 * <p>
 * 对应表：lg_tool_run
 */
@Data
@TableName("lg_tool_run")
public class ToolRunEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 项目 ID */
    private String projectId;

    /** 扫描版本 ID（可为空） */
    private String versionId;

    /** 工具配置 ID */
    private String toolEndpointId;

    /** 工具名称 */
    private String toolName;

    /** 工具类型：MCP / CLI / HOSTED_SEARCH / LOCAL */
    private String toolKind;

    /** 操作类型 */
    private String operation;

    /** 查询参数 hash */
    private String queryHash;

    /** 执行状态 */
    private String status;

    /** CLI 退出码 */
    private Integer exitCode;

    /** 耗时（毫秒） */
    private Long elapsedMs;

    /** 索引新鲜度 */
    private String indexFreshness;

    /** 输出 SHA-256 */
    private String stdoutSha256;

    /** 输出摘要（截断） */
    private String stdoutExcerpt;

    /** 错误摘要 */
    private String errorExcerpt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
