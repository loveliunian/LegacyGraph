package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.AuditLog;
import io.github.legacygraph.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志服务
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 分页查询审计日志
     */
    public PageResult<AuditLog> list(int pageNum, int pageSize,
            String operation, String operatorName, String status,
            LocalDateTime startTime, LocalDateTime endTime) {

        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AuditLog::getCreatedAt);

        if (StringUtils.hasText(operation)) {
            wrapper.like(AuditLog::getOperation, operation);
        }
        if (StringUtils.hasText(operatorName)) {
            wrapper.like(AuditLog::getOperatorName, operatorName);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AuditLog::getStatus, status);
        }
        if (startTime != null) {
            wrapper.ge(AuditLog::getCreatedAt, startTime);
        }
        if (endTime != null) {
            wrapper.le(AuditLog::getCreatedAt, endTime);
        }

        Page<AuditLog> page = new Page<>(pageNum, pageSize);
        Page<AuditLog> result = auditLogRepository.selectPage(page, wrapper);

        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 根据ID获取日志详情
     */
    public AuditLog getById(Long id) {
        return auditLogRepository.selectById(id);
    }

    /**
     * 清空日志
     */
    public void clear() {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        auditLogRepository.delete(wrapper);
    }

    /**
     * 删除指定日志
     */
    public boolean delete(Long id) {
        return auditLogRepository.deleteById(id) > 0;
    }

    /**
     * 统计日志总数
     */
    public long count() {
        return auditLogRepository.selectCount(null);
    }

    /**
     * 统计今天的日志数
     */
    public long countToday() {
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(AuditLog::getCreatedAt, startOfDay, endOfDay);
        return auditLogRepository.selectCount(wrapper);
    }
}
