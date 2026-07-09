package io.github.legacygraph.controller;

import io.github.legacygraph.analysis.CpgBuilder;
import io.github.legacygraph.analysis.CpgBuilder.CpgResult;
import io.github.legacygraph.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码属性图（CPG）控制器 —— P4a 基础框架。
 * <p>
 * 给定 Java 源文件路径与方法名，返回方法内 CFG 节点 + DFG 边的 JSON。
 * 不写库，纯内存对象。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/cpg")
@RequiredArgsConstructor
public class CpgController {

    private final CpgBuilder cpgBuilder;

    /**
     * GET /api/lg/projects/{projectId}/cpg?filePath=xxx&methodName=xxx
     * <p>
     * 构建指定方法的 CPG。
     *
     * @param projectId  项目 ID（当前基础框架未实际使用，仅作路径占位）
     * @param filePath   Java 源文件绝对路径
     * @param className  类名（可选，缺省取文件首个类）
     * @param methodName 方法名（必填）
     * @return CPG 结果
     */
    @GetMapping
    public Result<CpgResult> buildCpg(
            @PathVariable String projectId,
            @RequestParam String filePath,
            @RequestParam(required = false) String className,
            @RequestParam String methodName) {

        log.info("构建 CPG: projectId={}, filePath={}, className={}, methodName={}",
                projectId, filePath, className, methodName);

        if (filePath == null || filePath.isBlank()) {
            return Result.badRequest("filePath 不能为空");
        }
        if (methodName == null || methodName.isBlank()) {
            return Result.badRequest("methodName 不能为空");
        }

        try {
            CpgResult result = cpgBuilder.build(filePath, className, methodName);
            return Result.success(result);
        } catch (Exception e) {
            log.warn("构建 CPG 失败: filePath={}, methodName={}, err={}", filePath, methodName, e.getMessage());
            return Result.error("构建 CPG 失败: " + e.getMessage());
        }
    }
}
