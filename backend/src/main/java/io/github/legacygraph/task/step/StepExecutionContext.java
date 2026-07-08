package io.github.legacygraph.task.step;

import io.github.legacygraph.dto.AiScanConfig;
import lombok.Builder;
import lombok.Data;

import java.util.function.BooleanSupplier;

/**
 * 步骤执行上下文 — 承载单个 AI 扫描步骤执行所需的输入参数。
 */
@Data
@Builder
public class StepExecutionContext {
    private String projectId;
    private String versionId;
    private AiScanConfig config;
    private BooleanSupplier cancellationChecker;
}
