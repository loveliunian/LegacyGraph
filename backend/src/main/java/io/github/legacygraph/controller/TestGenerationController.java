package io.github.legacygraph.controller;

import io.github.legacygraph.agent.TestGenerationAgent;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.solution.TestGenerationRequest;
import io.github.legacygraph.dto.solution.TestGenerationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试生成 Controller（G-18）。
 * <p>独立于 {@link SolutionController}（后者构造函数参数较多），
 * 专门承接方案触发 TestGenerationAgent 生成测试骨架的端点。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "测试生成", description = "基于方案自动触发测试生成")
public class TestGenerationController {

    private final TestGenerationAgent testGenerationAgent;

    /**
     * 触发测试生成（G-18）。
     * <p>基于已存在的方案（Solution）及其步骤（SolutionStep），
     * 为每个带 {@code testDescription} 的步骤生成 JUnit 5 风格测试骨架。</p>
     *
     * @param projectId  项目 ID
     * @param solutionId 方案 ID
     * @param request    请求体（可选，默认 JUNIT5 + UNIT）
     * @return 测试生成结果
     */
    @Log(value = "触发方案测试生成", type = Log.OperationType.TEST)
    @PostMapping("/lg/projects/{projectId}/solutions/{solutionId}/generate-tests")
    @Operation(summary = "基于方案自动生成测试骨架",
            description = "加载方案与步骤，对每个带 testDescription 的步骤生成 JUnit 5 测试骨架；"
                    + "请求体可选，默认 testFramework=JUNIT5，testScope=UNIT")
    public Result<TestGenerationResult> generateTests(
            @PathVariable String projectId,
            @PathVariable String solutionId,
            @RequestBody(required = false) TestGenerationRequest request) {

        // 请求体可选：未提供时使用默认值
        String testFramework = "JUNIT5";
        if (request != null) {
            if (request.getTestFramework() != null && !request.getTestFramework().isBlank()) {
                testFramework = request.getTestFramework();
            }
            // testScope 当前仅做记录，未来可扩展为不同的测试生成策略
            log.debug("generateTests: projectId={}, solutionId={}, testScope={}",
                    projectId, solutionId, request.getTestScope());
        }

        TestGenerationResult result = testGenerationAgent.generateFromSolution(
                projectId, solutionId, testFramework);
        log.info("Tests generated: projectId={}, solutionId={}, status={}, count={}",
                projectId, solutionId, result.getStatus(),
                result.getGeneratedTests() != null ? result.getGeneratedTests().size() : 0);
        return Result.success(result);
    }
}
