package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统审计日志实体
 */
@Data
@TableName("lg_sys_operation_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 链路追踪ID
     */
    private String traceId;

    /**
     * 操作名称（描述）
     */
    private String operation;

    /**
     * 操作类型：INSERT/UPDATE/DELETE/QUERY/LOGIN/OTHER
     */
    private String operationType;

    /**
     * 方法签名（DB 列名: method）
     */
    private String method;

    /**
     * 请求URI
     */
    private String requestUri;

    /**
     * 请求方法
     */
    private String requestMethod;

    /**
     * 客户端IP
     */
    private String clientIp;

    /**
     * User-Agent
     */
    private String userAgent;

    /**
     * 操作人ID
     */
    private String operatorId;

    /**
     * 操作人名称
     */
    private String operatorName;

    /**
     * 状态: SUCCESS/FAILED
     */
    private String status;

    /**
     * 执行耗时(毫秒)
     */
    private Long durationMs;

    /**
     * 请求参数
     */
    private String requestParams;

    /**
     * 返回结果
     */
    private String responseResult;

    /**
     * 异常堆栈
     */
    private String errorStack;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
