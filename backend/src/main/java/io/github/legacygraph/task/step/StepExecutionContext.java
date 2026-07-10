package io.github.legacygraph.task.step;

import io.github.legacygraph.dto.AiScanConfig;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Set;
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

    /** 变更文件路径集合（增量模式下用于缩小扫描范围） */
    @Builder.Default
    private Set<String> changedFilePaths = Collections.emptySet();

    /** 受影响节点 ID 集合（增量模式下用于缩小处理范围） */
    @Builder.Default
    private Set<String> affectedNodeIds = Collections.emptySet();

    /** 是否增量模式：false 表示全量扫描，true 表示仅处理变更部分 */
    private boolean incremental;
}
