package io.github.legacygraph.task.step;

import lombok.Builder;
import lombok.Data;

/**
 * 步骤执行结果。
 *
 * <p>H17: 新增 {@code warning} 字段，用于表达"部分成功/有警告"的中间状态。
 * 当部分子任务失败但不影响整体流程时，{@code success=true, warning=true}，
 * 调用方可据此上报 WARNING 子任务状态。</p>
 */
@Data
@Builder
public class StepExecutionResult {
    private boolean success;
    /** H17: 部分失败/有警告（success=true 但有部分子任务失败） */
    private boolean warning;
    private String message;
    private int processedCount;
}
