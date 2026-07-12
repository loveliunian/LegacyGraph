package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.QaAuditLog;
import io.github.legacygraph.repository.QaAuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA 审计日志管理 API（H22）。
 *
 * <p>暴露 QA 审计日志查询端点，供管理员查看 ACL 阻断、版本不匹配等安全审计事件。
 * 审计日志由 {@link io.github.legacygraph.service.qa.AclFilterService#audit} 写入，
 * 拒答分支由 {@link io.github.legacygraph.agent.EnhancedQaAgent} 触发。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/qa/audit")
@RequiredArgsConstructor
@Tag(name = "QA 审计日志", description = "ACL 阻断、版本不匹配等安全审计事件查询")
public class QaAdminController {

    private final QaAuditLogRepository qaAuditLogRepository;

    /**
     * 分页查询 QA 审计日志。
     *
     * @param projectId     项目 ID（可选，不传则查全部）
     * @param principal     触发主体（可选，如 user:alice）
     * @param blockedReason 拦截原因关键词（可选，模糊匹配）
     * @param pageNum       页码，默认 1
     * @param pageSize      每页条数，默认 20
     * @return 分页审计日志列表
     */
    @GetMapping
    @Operation(summary = "分页查询 QA 审计日志", description = "支持按项目、主体、拦截原因过滤")
    public Result<PageResult<QaAuditLog>> listAuditLogs(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String blockedReason,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {

        LambdaQueryWrapper<QaAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(projectId)) {
            wrapper.eq(QaAuditLog::getProjectId, projectId);
        }
        if (StringUtils.hasText(principal)) {
            wrapper.eq(QaAuditLog::getPrincipal, principal);
        }
        if (StringUtils.hasText(blockedReason)) {
            wrapper.like(QaAuditLog::getBlockedReason, blockedReason);
        }
        wrapper.orderByDesc(QaAuditLog::getCreatedAt);

        Page<QaAuditLog> page = qaAuditLogRepository.selectPage(
                new Page<>(pageNum, pageSize), wrapper);

        PageResult<QaAuditLog> result = PageResult.of(
                page.getRecords(), page.getTotal(), pageNum, pageSize);
        return Result.success(result);
    }
}
