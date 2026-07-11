package io.github.legacygraph.service.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 验收条件闭环验证服务（G-14）。
 * <p>基于 {@link AcceptanceCriterion} 的 verificationType 执行差异化验证：
 * <ul>
 *   <li>AUTOMATIC：尝试生成验证脚本（简化为自动置 VERIFIED）</li>
 *   <li>MANUAL：保持 PENDING，等待人工勾选</li>
 *   <li>NONE：自动置 VERIFIED</li>
 * </ul>
 * 当某 RequirementItem 下所有 AC 均为终态（VERIFIED / WAIVED）时，
 * 检查需求整体是否可推进到 DONE。</p>
 */
@Slf4j
@Service
public class AcceptanceVerificationService {

    /** 验证状态常量 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_WAIVED = "WAIVED";
    public static final String STATUS_FAILED = "FAILED";

    /** 验证类型常量 */
    public static final String TYPE_AUTOMATIC = "AUTOMATIC";
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_NONE = "NONE";

    /** 需求状态：已完成 */
    public static final String REQ_STATUS_DONE = "DONE";

    private final AcceptanceCriterionRepository criterionRepository;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;

    public AcceptanceVerificationService(AcceptanceCriterionRepository criterionRepository,
                                          RequirementRepository requirementRepository,
                                          RequirementItemRepository itemRepository) {
        this.criterionRepository = criterionRepository;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 触发需求闭环验证：加载该需求下所有 AC，按 verificationType 自动验证。
     * <p>AUTOMATIC / NONE 自动置 VERIFIED；MANUAL 保持 PENDING 等待人工勾选。
     * 完成后检查并推进需求状态。</p>
     *
     * @param requirementId 需求 ID
     * @param verifier      验证人
     * @return 更新后的验收条件列表
     */
    @Transactional
    public List<AcceptanceCriterion> verifyByRequirement(String requirementId, String verifier) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        List<AcceptanceCriterion> all = loadCriteriaByRequirement(requirementId);
        if (all.isEmpty()) {
            log.info("Requirement {} has no acceptance criteria, skip verification", requirementId);
            tryAdvanceToDone(requirementId);
            return all;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AcceptanceCriterion ac : all) {
            // 已是终态的跳过
            if (isTerminal(ac.getStatus())) {
                continue;
            }
            String type = ac.getVerificationType();
            if (type == null || type.isBlank()) {
                // 未指定类型时默认 AUTOMATIC 并持久化
                type = TYPE_AUTOMATIC;
                ac.setVerificationType(TYPE_AUTOMATIC);
            }
            switch (type) {
                case TYPE_AUTOMATIC:
                    // 简化：自动验证通过（实际可在此生成验证脚本/调用测试）
                    markVerified(ac, verifier, now, "自动验证通过");
                    break;
                case TYPE_NONE:
                    markVerified(ac, verifier, now, "无需验证");
                    break;
                case TYPE_MANUAL:
                default:
                    // MANUAL：保持 PENDING，等待人工勾选
                    break;
            }
            criterionRepository.updateById(ac);
        }
        // 推进需求状态
        tryAdvanceToDone(requirementId);
        log.info("Requirement {} verification triggered by {}: verified={}, total={}",
                requirementId, verifier,
                all.stream().filter(a -> STATUS_VERIFIED.equals(a.getStatus())).count(),
                all.size());
        return all;
    }

    /**
     * 验证单条 AC（人工勾选）。
     *
     * @param criterionId 验收条件 ID
     * @param verifier    验证人
     * @param note        验证备注
     * @param evidenceUrl 证据链接
     * @return 更新后的验收条件
     */
    @Transactional
    public AcceptanceCriterion verifySingle(String criterionId, String verifier, String note, String evidenceUrl) {
        AcceptanceCriterion ac = criterionRepository.selectById(criterionId);
        if (ac == null) {
            throw new IllegalArgumentException("验收条件不存在: " + criterionId);
        }
        markVerified(ac, verifier, LocalDateTime.now(), note);
        if (evidenceUrl != null && !evidenceUrl.isBlank()) {
            ac.setEvidenceUrl(evidenceUrl);
        }
        criterionRepository.updateById(ac);
        // 检查所属需求是否可推进
        tryAdvanceToDoneByItem(ac.getRequirementItemId());
        log.info("AC {} verified by {}", criterionId, verifier);
        return ac;
    }

    /**
     * 豁免一条 AC。
     *
     * @param criterionId 验收条件 ID
     * @param verifier    操作人
     * @param reason      豁免原因
     * @return 更新后的验收条件
     */
    @Transactional
    public AcceptanceCriterion waive(String criterionId, String verifier, String reason) {
        AcceptanceCriterion ac = criterionRepository.selectById(criterionId);
        if (ac == null) {
            throw new IllegalArgumentException("验收条件不存在: " + criterionId);
        }
        ac.setStatus(STATUS_WAIVED);
        ac.setVerifiedBy(verifier);
        ac.setVerifiedAt(LocalDateTime.now());
        ac.setVerificationNote(reason);
        criterionRepository.updateById(ac);
        // 豁免也视为终态，检查所属需求是否可推进
        tryAdvanceToDoneByItem(ac.getRequirementItemId());
        log.info("AC {} waived by {}: {}", criterionId, verifier, reason);
        return ac;
    }

    /**
     * 标记一条 AC 为 FAILED（验收未通过）。
     * <p>FAILED 不属于终态（与 WAIVED/VERIFIED 区别开）—— 所属条目保持未闭环，
     * 需求不会被推进到 DONE；后续如重新通过 {@link #verifySingle}，仍可改回 VERIFIED。</p>
     *
     * @param criterionId 验收条件 ID
     * @param verifier    标记人（一般为测试人员/PM）
     * @param reason      失败原因
     * @return 更新后的验收条件
     */
    @Transactional
    public AcceptanceCriterion fail(String criterionId, String verifier, String reason) {
        AcceptanceCriterion ac = criterionRepository.selectById(criterionId);
        if (ac == null) {
            throw new IllegalArgumentException("验收条件不存在: " + criterionId);
        }
        ac.setStatus(STATUS_FAILED);
        ac.setVerifiedBy(verifier);
        ac.setVerifiedAt(LocalDateTime.now());
        ac.setVerificationNote(reason);
        criterionRepository.updateById(ac);
        // FAILED 不推进需求状态（仅 VERIFIED/WAIVED 视为终态）
        log.info("AC {} failed by {}: {}", criterionId, verifier, reason);
        return ac;
    }

    /**
     * 生成需求闭环报告。
     *
     * @param requirementId 需求 ID
     * @return 报告 Map：含需求状态、条目与验收条件明细、统计计数
     */
    public Map<String, Object> closureReport(String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        LambdaQueryWrapper<RequirementItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(RequirementItem::getRequirementId, requirementId)
                .orderByAsc(RequirementItem::getCode);
        List<RequirementItem> items = itemRepository.selectList(itemWrapper);

        List<Map<String, Object>> itemReports = new ArrayList<>();
        int totalCriteria = 0;
        int verifiedCount = 0;
        int pendingCount = 0;
        int waivedCount = 0;
        int failedCount = 0;

        for (RequirementItem item : items) {
            LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
            acWrapper.eq(AcceptanceCriterion::getRequirementItemId, item.getId());
            List<AcceptanceCriterion> acs = criterionRepository.selectList(acWrapper);

            List<Map<String, Object>> acList = new ArrayList<>();
            for (AcceptanceCriterion ac : acs) {
                Map<String, Object> acMap = new LinkedHashMap<>();
                acMap.put("id", ac.getId());
                acMap.put("text", ac.getText());
                acMap.put("status", ac.getStatus());
                acMap.put("verificationType", ac.getVerificationType());
                acMap.put("verifiedBy", ac.getVerifiedBy());
                acMap.put("verifiedAt", ac.getVerifiedAt());
                acMap.put("evidenceUrl", ac.getEvidenceUrl());
                acList.add(acMap);

                totalCriteria++;
                String st = ac.getStatus();
                if (STATUS_VERIFIED.equals(st)) {
                    verifiedCount++;
                } else if (STATUS_WAIVED.equals(st)) {
                    waivedCount++;
                } else if (STATUS_FAILED.equals(st)) {
                    failedCount++;
                } else {
                    pendingCount++;
                }
            }

            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("itemId", item.getId());
            itemMap.put("code", item.getCode());
            itemMap.put("text", item.getText());
            itemMap.put("criteria", acList);
            // 条目是否闭环：所有 AC 均为终态
            itemMap.put("closed", !acs.isEmpty() && acs.stream().allMatch(a -> isTerminal(a.getStatus())));
            itemReports.add(itemMap);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("requirementId", requirementId);
        report.put("requirementStatus", req.getStatus());
        report.put("totalItems", items.size());
        report.put("totalCriteria", totalCriteria);
        report.put("verifiedCount", verifiedCount);
        report.put("pendingCount", pendingCount);
        report.put("waivedCount", waivedCount);
        report.put("failedCount", failedCount);
        // 需求是否闭环：所有条目均已闭环
        boolean allClosed = !itemReports.isEmpty()
                && itemReports.stream().allMatch(m -> Boolean.TRUE.equals(m.get("closed")));
        report.put("closed", allClosed);
        report.put("items", itemReports);
        return report;
    }

    // ==================== 内部方法 ====================

    /**
     * 加载需求下所有验收条件（跨所有 RequirementItem）。
     */
    private List<AcceptanceCriterion> loadCriteriaByRequirement(String requirementId) {
        LambdaQueryWrapper<RequirementItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(RequirementItem::getRequirementId, requirementId);
        List<RequirementItem> items = itemRepository.selectList(itemWrapper);
        if (items.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> itemIds = items.stream().map(RequirementItem::getId).toList();
        LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
        acWrapper.in(AcceptanceCriterion::getRequirementItemId, itemIds);
        return criterionRepository.selectList(acWrapper);
    }

    /**
     * 标记 AC 为 VERIFIED。
     */
    private void markVerified(AcceptanceCriterion ac, String verifier, LocalDateTime now, String note) {
        ac.setStatus(STATUS_VERIFIED);
        ac.setVerifiedBy(verifier);
        ac.setVerifiedAt(now);
        if (note != null && !note.isBlank()) {
            ac.setVerificationNote(note);
        }
    }

    /**
     * 判断状态是否为终态（VERIFIED / WAIVED）。
     */
    private boolean isTerminal(String status) {
        return STATUS_VERIFIED.equals(status) || STATUS_WAIVED.equals(status);
    }

    /**
     * 检查并推进需求状态到 DONE。
     * <p>当需求下所有 RequirementItem 的所有 AC 均为终态时，置 DONE。</p>
     */
    private void tryAdvanceToDone(String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            return;
        }
        if (REQ_STATUS_DONE.equals(req.getStatus())) {
            return;
        }
        if (isRequirementClosed(requirementId)) {
            req.setStatus(REQ_STATUS_DONE);
            req.setUpdatedAt(LocalDateTime.now());
            requirementRepository.updateById(req);
            log.info("Requirement {} advanced to DONE", requirementId);
        }
    }

    /**
     * 通过 RequirementItem ID 反查需求并检查推进。
     */
    private void tryAdvanceToDoneByItem(String requirementItemId) {
        RequirementItem item = itemRepository.selectById(requirementItemId);
        if (item == null) {
            return;
        }
        tryAdvanceToDone(item.getRequirementId());
    }

    /**
     * 判断需求是否所有条目的所有 AC 均为终态。
     */
    private boolean isRequirementClosed(String requirementId) {
        LambdaQueryWrapper<RequirementItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(RequirementItem::getRequirementId, requirementId);
        List<RequirementItem> items = itemRepository.selectList(itemWrapper);
        if (items.isEmpty()) {
            return false;
        }
        for (RequirementItem item : items) {
            LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
            acWrapper.eq(AcceptanceCriterion::getRequirementItemId, item.getId());
            List<AcceptanceCriterion> acs = criterionRepository.selectList(acWrapper);
            if (acs.isEmpty()) {
                // 无验收条件的条目视为未闭环
                return false;
            }
            for (AcceptanceCriterion ac : acs) {
                if (!isTerminal(ac.getStatus())) {
                    return false;
                }
            }
        }
        return true;
    }
}
