package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.QaAuditLog;
import io.github.legacygraph.repository.QaAuditLogRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ACL 过滤服务 — 负责证据级 ACL 校验、版本匹配检查与审计日志记录。
 *
 * <p>ACL 判断逻辑：文档主体列表（docPrincipals）为空或 null 时允许通过（无 ACL 限制）；
 * 否则与上下文主体列表取交集，有交集则通过。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AclFilterService {

    private final QaAuditLogRepository qaAuditLogRepository;

    /**
     * 判断 ACL 是否通过。
     *
     * <p>规则：
     * <ul>
     *   <li>docPrincipals 为 null 或空 → 通过（无 ACL 限制的公开证据）</li>
     *   <li>ctxPrincipals 为 null 或空且 docPrincipals 非空 → 拒绝（无任何匹配主体）</li>
     *   <li>两者均非空 → 取交集，有交集则通过</li>
     * </ul>
     *
     * @param docPrincipals 证据/文档要求的安全主体列表
     * @param ctxPrincipals  当前访问上下文持有的安全主体列表
     * @return true 表示 ACL 通过，false 表示被拦截
     */
    public boolean aclPass(List<String> docPrincipals, List<String> ctxPrincipals) {
        if (docPrincipals == null || docPrincipals.isEmpty()) {
            return true;
        }
        if (ctxPrincipals == null || ctxPrincipals.isEmpty()) {
            return false;
        }
        // 归一化为小写后取交集，规避大小写差异导致的误拦截
        List<String> normalizedCtx = ctxPrincipals.stream()
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .collect(Collectors.toList());
        for (String p : docPrincipals) {
            if (p != null && normalizedCtx.contains(p.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 版本匹配检查 — 证据所属发布版本与当前请求版本是否一致。
     *
     * <p>docReleaseId 为 null 或空时视为无版本约束，允许通过（向后兼容）；
     * 否则需与 ctxReleaseId 严格相等（null ctxReleaseId 视为不匹配）。</p>
     *
     * @param docReleaseId 证据所属的图谱发布版本 ID
     * @param ctxReleaseId 当前请求的图谱发布版本 ID
     * @return true 表示版本匹配，false 表示版本不匹配
     */
    public boolean versionMatch(String docReleaseId, String ctxReleaseId) {
        if (docReleaseId == null || docReleaseId.isBlank()) {
            return true;
        }
        return docReleaseId.equals(ctxReleaseId);
    }

    /**
     * 记录 QA 审计日志。
     *
     * <p>审计日志写入失败不影响主链路，仅记录 warn 日志。</p>
     *
     * @param projectId      项目 ID
     * @param graphReleaseId 图谱发布版本 ID
     * @param principal      触发审计的主体（如 user:alice）
     * @param questionHash   问题哈希（用于关联具体问答请求）
     * @param aclHash        ACL 哈希（标识访问上下文）
     * @param blockedReason  拦截原因（如 ACL_DENIED / VERSION_MISMATCH，无拦截时为 null）
     */
    public void audit(String projectId, String graphReleaseId, String principal,
                      String questionHash, String aclHash, String blockedReason) {
        try {
            QaAuditLog auditLog = new QaAuditLog();
            auditLog.setId(IdUtil.fastUUID());
            auditLog.setProjectId(projectId);
            auditLog.setGraphReleaseId(graphReleaseId);
            auditLog.setPrincipal(principal);
            auditLog.setQuestionHash(questionHash);
            auditLog.setAclHash(aclHash);
            auditLog.setBlockedReason(blockedReason);
            auditLog.setCreatedAt(LocalDateTime.now());
            qaAuditLogRepository.insert(auditLog);
        } catch (Exception e) {
            // 审计日志写入失败不阻断主链路
            log.warn("Failed to write QA audit log: projectId={}, principal={}, reason={}",
                projectId, principal, e.getMessage());
        }
    }
}
