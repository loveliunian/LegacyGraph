package io.github.legacygraph.task.step;

import io.github.legacygraph.common.ScanStep;

/**
 * AI 扫描步骤执行器 — 步骤执行器模式（Step Executor Pattern）。
 *
 * <p>每个实现类负责一个职责单一的 AI 编排子步骤，{@link io.github.legacygraph.task.AiScanOrchestrator}
 * 只保留控制流编排（ScanStep 状态机推进、取消检查、门控），把具体业务逻辑委托给步骤执行器。</p>
 */
public interface AiScanStepExecutor {

    /**
     * 执行该步骤。
     */
    StepExecutionResult execute(StepExecutionContext ctx);

    /**
     * 步骤名称（返回任务类型名，如 "AI_DOC_EXTRACT"）。
     */
    String getStepName();

    /**
     * 执行顺序（升序）。
     */
    int getOrder();

    /**
     * 是否应执行该步骤（默认执行）。
     */
    default boolean shouldExecute(StepExecutionContext ctx) {
        return true;
    }

    /**
     * 该步骤对应的状态机步骤。
     */
    ScanStep getScanStep();
}
