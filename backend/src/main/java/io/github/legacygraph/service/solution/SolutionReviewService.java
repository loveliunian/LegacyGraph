package io.github.legacygraph.service.solution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dto.solution.ApproveRequest;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionAudit;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.SolutionAuditRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import io.github.legacygraph.service.change.ChangeTaskService;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class SolutionReviewService {

    private static final String STATUS_READY_FOR_REVIEW = "READY_FOR_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_APPROVE_WITH_REVISION = "APPROVE_WITH_REVISION";
    private static final String DECISION_REJECT = "REJECT";

    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;
    private final SolutionAuditRepository auditRepository;
    private final SolutionToChangeTaskBridge solutionToChangeTaskBridge;
    private final ChangeTaskService changeTaskService;

    public SolutionReviewService(SolutionRepository solutionRepository,
                                  SolutionStepRepository stepRepository,
                                  SolutionAuditRepository auditRepository,
                                  SolutionToChangeTaskBridge solutionToChangeTaskBridge,
                                  ChangeTaskService changeTaskService) {
        this.solutionRepository = solutionRepository;
        this.stepRepository = stepRepository;
        this.auditRepository = auditRepository;
        this.solutionToChangeTaskBridge = solutionToChangeTaskBridge;
        this.changeTaskService = changeTaskService;
    }

    @Transactional
    public Solution approve(String solutionId, ApproveRequest request) {
        Solution solution = requireSolution(solutionId);
        String beforeStatus = solution.getStatus();

        if (!STATUS_READY_FOR_REVIEW.equals(beforeStatus)) {
            throw new IllegalStateException(
                    "只有 READY_FOR_REVIEW 状态的方案才能审批，当前状态: " + beforeStatus);
        }

        String decision = request.getDecision() != null
                ? request.getDecision().toUpperCase()
                : DECISION_APPROVE;
        String reviewer = request.getReviewer();
        String comment = request.getComment();
        LocalDateTime now = LocalDateTime.now();

        if (DECISION_APPROVE.equals(decision)) {
            solution.setStatus(STATUS_APPROVED);
            solution.setReviewer(reviewer);
            solution.setReviewComment(comment);
            solution.setReviewedAt(now);
            solution.setUpdatedAt(now);
            solutionRepository.updateById(solution);

            createAudit(solutionId, reviewer, beforeStatus, STATUS_APPROVED, decision, comment);
            log.info("Solution approved: solutionId={}, reviewer={}", solutionId, reviewer);

        } else if (DECISION_APPROVE_WITH_REVISION.equals(decision)) {
            Solution newSolution = createRevisedSolution(solution, request);
            solution.setStatus(STATUS_SUPERSEDED);
            solution.setUpdatedAt(now);
            solutionRepository.updateById(solution);

            createAudit(solutionId, reviewer, beforeStatus, STATUS_SUPERSEDED, decision, comment);
            createAudit(newSolution.getId(), reviewer, "DRAFT", STATUS_APPROVED, decision, comment);
            log.info("Solution approved with revision: oldId={}, newId={}, reviewer={}",
                    solutionId, newSolution.getId(), reviewer);
            solution = newSolution;

        } else if (DECISION_REJECT.equals(decision)) {
            if (comment == null || comment.isBlank()) {
                throw new IllegalArgumentException("驳回方案必须填写原因（comment 不能为空）");
            }
            solution.setStatus(STATUS_REJECTED);
            solution.setReviewer(reviewer);
            solution.setReviewComment(comment);
            solution.setReviewedAt(now);
            solution.setUpdatedAt(now);
            solutionRepository.updateById(solution);

            createAudit(solutionId, reviewer, beforeStatus, STATUS_REJECTED, decision, comment);
            log.info("Solution rejected: solutionId={}, reviewer={}, reason={}", solutionId, reviewer, comment);

        } else {
            throw new IllegalArgumentException("无效的审批决定: " + decision
                    + "（应为 APPROVE / APPROVE_WITH_REVISION / REJECT）");
        }

        return solution;
    }

    @Transactional
    public Solution reject(String solutionId, String reviewer, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("驳回原因不能为空");
        }
        ApproveRequest req = new ApproveRequest();
        req.setReviewer(reviewer);
        req.setDecision(DECISION_REJECT);
        req.setComment(reason);
        return approve(solutionId, req);
    }

    public List<SolutionAudit> listAudits(String solutionId) {
        LambdaQueryWrapper<SolutionAudit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionAudit::getSolutionId, solutionId)
                .orderByDesc(SolutionAudit::getCreatedAt);
        return auditRepository.selectList(wrapper);
    }

    private Solution createRevisedSolution(Solution original, ApproveRequest request) {
        LocalDateTime now = LocalDateTime.now();

        Solution newSol = new Solution();
        newSol.setId(IdUtil.fastUUID());
        newSol.setProjectId(original.getProjectId());
        newSol.setRequirementId(original.getRequirementId());
        newSol.setStatus(STATUS_APPROVED);
        newSol.setSummary(original.getSummary());
        newSol.setAnalysisJson(original.getAnalysisJson());
        newSol.setImpactResultJson(original.getImpactResultJson());
        newSol.setEstimatedCostJson(original.getEstimatedCostJson());
        newSol.setRiskAssessmentJson(original.getRiskAssessmentJson());
        newSol.setReviewer(request.getReviewer());
        newSol.setReviewComment(request.getComment());
        newSol.setReviewedAt(now);
        newSol.setCreatedAt(now);
        newSol.setUpdatedAt(now);
        solutionRepository.insert(newSol);

        LambdaQueryWrapper<SolutionStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SolutionStep::getSolutionId, original.getId())
                .orderByAsc(SolutionStep::getStepIndex);
        List<SolutionStep> originalSteps = stepRepository.selectList(stepWrapper);

        for (SolutionStep origStep : originalSteps) {
            SolutionStep newStep = new SolutionStep();
            newStep.setId(IdUtil.fastUUID());
            newStep.setSolutionId(newSol.getId());
            newStep.setStepIndex(origStep.getStepIndex());
            newStep.setTitle(origStep.getTitle());
            newStep.setDescription(origStep.getDescription());
            newStep.setFilePath(origStep.getFilePath());
            newStep.setSymbolName(origStep.getSymbolName());
            newStep.setEvidenceIds(origStep.getEvidenceIds());
            newStep.setActionType(origStep.getActionType());
            newStep.setTestDescription(origStep.getTestDescription());
            newStep.setRollbackDescription(origStep.getRollbackDescription());
            newStep.setCodeSnippet(origStep.getCodeSnippet());
            newStep.setCodeLanguage(origStep.getCodeLanguage());
            newStep.setCreatedAt(now);
            stepRepository.insert(newStep);
        }

        return newSol;
    }

    private void createAudit(String solutionId, String reviewer,
                             String beforeStatus, String afterStatus,
                             String decision, String comment) {
        SolutionAudit audit = new SolutionAudit();
        audit.setId(IdUtil.fastUUID());
        audit.setSolutionId(solutionId);
        audit.setReviewer(reviewer);
        audit.setBeforeStatus(beforeStatus);
        audit.setAfterStatus(afterStatus);
        audit.setDecision(decision);
        audit.setComment(comment);
        audit.setCreatedAt(LocalDateTime.now());
        auditRepository.insert(audit);
    }

    private Solution requireSolution(String solutionId) {
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }
        return solution;
    }
}
