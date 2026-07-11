package io.github.legacygraph.repository;

import io.github.legacygraph.entity.QaAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * QA 审计日志 Mapper。
 */
@Mapper
public interface QaAuditLogRepository extends LegacyBaseMapper<QaAuditLog> {
}
