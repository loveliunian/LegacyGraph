package io.github.legacygraph.repository;

import io.github.legacygraph.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogRepository extends LegacyBaseMapper<AuditLog> {
}
