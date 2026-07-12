package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.processmining.Pm4PyClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 流程挖掘 API（H04）。
 *
 * <p>暴露 PM4Py 一致性校验端点，支持通过 HTTP 触发 conformance checking。
 * 当 pm4py 环境不可用时返回 {@code available=false} + errorMessage，HTTP 仍 200。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/process-mining")
@RequiredArgsConstructor
public class ProcessMiningController {

    private final Pm4PyClient pm4PyClient;

    /**
     * 执行流程一致性校验。
     *
     * @param projectId     项目 ID
     * @param versionId     版本 ID
     * @param casesCsvPath  用例 CSV 路径（含 case_id, activity, timestamp）
     * @param modelJsonPath 流程模型 JSON 路径
     * @return conformance 结果（fitness / precision / generalization）
     */
    @PostMapping("/conformance")
    public Result<Pm4PyClient.ConformanceResult> runConformance(
            @PathVariable String projectId,
            @RequestParam String versionId,
            @RequestParam String casesCsvPath,
            @RequestParam String modelJsonPath) {

        log.info("流程一致性校验请求: projectId={}, versionId={}, casesCsv={}, modelJson={}",
                projectId, versionId, casesCsvPath, modelJsonPath);

        // 检查 pm4py 是否可用
        if (!pm4PyClient.isAvailable()) {
            log.warn("PM4Py not available, returning degraded result: projectId={}", projectId);
            return Result.ok(Pm4PyClient.ConformanceResult.builder()
                    .available(false)
                    .errorMessage("pm4py not installed or python3 not in PATH")
                    .build());
        }

        Pm4PyClient.ConformanceResult result = pm4PyClient.runConformance(casesCsvPath, modelJsonPath);
        return Result.ok(result);
    }

    /**
     * 检查 PM4Py 是否可用（供前端决定是否显示流程挖掘入口）。
     */
    @GetMapping("/availability")
    public Result<Pm4PyClient.ConformanceResult> checkAvailability(@PathVariable String projectId) {
        boolean available = pm4PyClient.isAvailable();
        return Result.ok(Pm4PyClient.ConformanceResult.builder()
                .available(available)
                .errorMessage(available ? null : "pm4py not installed")
                .build());
    }
}
