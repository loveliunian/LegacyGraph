package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QA 审计日志实体 — 记录 ACL 拦截与版本不匹配等安全审计事件。
 */
@Data
@TableName("lg_qa_audit_log")
public class QaAuditLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String graphReleaseId;

    private String principal;

    private String questionHash;

    private String aclHash;

    private String blockedReason;

    private LocalDateTime createdAt;
}
