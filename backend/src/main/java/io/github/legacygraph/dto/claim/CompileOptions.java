package io.github.legacygraph.dto.claim;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Claim 编译选项。
 */
@Data
@Builder
public class CompileOptions {

    /** 是否 dry-run（只输出差异，不写图谱） */
    @Builder.Default
    private boolean dryRun = false;

    /** 是否包含 PENDING_CONFIRM 状态的 Claim */
    @Builder.Default
    private boolean includePending = false;

    /** 最小置信度阈值 */
    @Builder.Default
    private BigDecimal minConfidence = new BigDecimal("0.85");

    /** 限制编译的 subjectType（空表示全部） */
    @Builder.Default
    private Set<String> subjectTypes = new HashSet<>();

    /** 限制编译的 predicate（空表示全部） */
    @Builder.Default
    private Set<String> predicates = new HashSet<>();

    /** 是否为低置信 Claim 生成审核任务 */
    @Builder.Default
    private boolean emitReviewTasks = false;
}
