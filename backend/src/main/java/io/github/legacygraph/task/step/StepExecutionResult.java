package io.github.legacygraph.task.step;

import lombok.Builder;
import lombok.Data;

/**
 * 步骤执行结果。
 */
@Data
@Builder
public class StepExecutionResult {
    private boolean success;
    private String message;
    private int processedCount;
}
