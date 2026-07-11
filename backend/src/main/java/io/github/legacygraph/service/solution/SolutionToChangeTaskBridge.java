package io.github.legacygraph.service.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.service.change.ChangeTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 方案到变更任务的桥接服务（G8）。
 * <p>将已批准（APPROVED）的 {@link Solution} 转为 {@link ChangeTask}，
 * 打通"需求方案"与"变更执行"两条流水线。
 * Solution 状态机：DRAFT → READY_FOR_REVIEW / NEEDS_INPUT → APPROVED / REJECTED。
 * ChangeTask 状态机：OPEN → IMPACT_READY → PATCH_DRAFTED → VALIDATING → VALIDATION_PASSED → PR_READY → MERGED。</p>
 */
@Slf4j
@Service
public class SolutionToChangeTaskBridge {

    private static final String STATUS_APPROVED = "APPROVED";

    private final SolutionRepository solutionRepository;
    private final ChangeTaskService changeTaskService;
    private final ObjectMapper objectMapper;

    public SolutionToChangeTaskBridge(SolutionRepository solutionRepository,
                                       ChangeTaskService changeTaskService,
                                       ObjectMapper objectMapper) {
        this.solutionRepository = solutionRepository;
        this.changeTaskService = changeTaskService;
        this.objectMapper = objectMapper;
    }

    /**
     * 将已批准方案转为变更任务。
     * <p>校验方案状态为 APPROVED，从 summary 构建 SolutionPlan 并推断任务类型，
     * 调用 {@link ChangeTaskService#createTask} 创建任务，回写 solution.changeTaskId。</p>
     *
     * @param solutionId 方案 ID
     * @param projectId  项目 ID
     * @param versionId  扫描版本 ID
     * @return 创建的变更任务
     * @throws IllegalArgumentException 方案不存在
     * @throws IllegalStateException  方案状态非 APPROVED
     */
    public ChangeTask bridge(String solutionId, String projectId, String versionId) {
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }
        if (!STATUS_APPROVED.equals(solution.getStatus())) {
            throw new IllegalStateException(
                    "方案状态非 APPROVED，无法桥接: " + solution.getStatus());
        }

        // 从 summary 构建 SolutionPlan 用于推断任务类型
        SolutionPlan plan = buildPlanFromSolution(solution);

        String taskType = inferTaskType(plan);
        String title = solution.getSummary();
        String inputIssue = solution.getSummary();

        ChangeTask task = changeTaskService.createTask(
                projectId, versionId, taskType, title, inputIssue);

        solution.setChangeTaskId(task.getId());
        solutionRepository.updateById(solution);

        log.info("Solution bridged to ChangeTask: solutionId={}, changeTaskId={}, taskType={}",
                solutionId, task.getId(), taskType);
        return task;
    }

    /**
     * 根据方案步骤推断变更任务类型。
     * <p>统计 steps 的 actionType 分布与文件路径特征：
     * <ul>
     *   <li>含 .sql 或 migration 文件路径 → UPGRADE（ChangeTaskService 不支持 MIGRATION，映射为 UPGRADE）</li>
     *   <li>全 CREATE → REFACTOR（ChangeTaskService 不支持 NEW_FEATURE，映射为 REFACTOR）</li>
     *   <li>默认 → REFACTOR</li>
     * </ul>
     * 仅返回 ChangeTaskService 支持的类型：REFACTOR/UPGRADE/BUGFIX/ADD_COLUMN。</p>
     *
     * @param plan 方案计划
     * @return 任务类型
     */
    public String inferTaskType(SolutionPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return "REFACTOR";
        }

        List<SolutionPlanStep> steps = plan.getSteps();
        boolean allCreate = true;
        boolean hasSqlOrMigration = false;

        for (SolutionPlanStep step : steps) {
            String actionType = step.getActionType();
            if (actionType == null || !"CREATE".equals(actionType)) {
                allCreate = false;
            }
            String filePath = step.getFilePath();
            if (filePath != null) {
                String lower = filePath.toLowerCase();
                if (lower.endsWith(".sql") || lower.contains("migration")) {
                    hasSqlOrMigration = true;
                }
            }
        }

        if (hasSqlOrMigration) {
            return "UPGRADE";
        }
        return "REFACTOR";
    }

    /**
     * 从 Solution 实体构建 SolutionPlan（基于 summary）。
     * <p>SolutionPlan 的 steps 未持久化在主表（落库为 lg_solution_step），
     * 此处仅用 summary 构建简化计划用于推断任务类型；如需完整 steps 可注入 SolutionStepRepository。</p>
     */
    private SolutionPlan buildPlanFromSolution(Solution solution) {
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary(solution.getSummary());
        return plan;
    }
}
