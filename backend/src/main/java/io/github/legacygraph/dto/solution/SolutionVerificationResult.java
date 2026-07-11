package io.github.legacygraph.dto.solution;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 方案确定性校验结果（Task 10）。
 * <p>由 {@code SolutionVerifier} 对方案步骤做 6 类确定性检查后产出：
 * <ol>
 *   <li>文件存在：步骤引用的文件路径在项目中存在</li>
 *   <li>符号存在：步骤引用的 symbolName 在图谱中存在</li>
 *   <li>高风险覆盖：影响分析标记为 HIGH/CRITICAL 的节点都被方案步骤覆盖</li>
 *   <li>测试覆盖：每个步骤都有测试描述</li>
 *   <li>证据有效：步骤引用的证据 ID 在图谱中存在</li>
 *   <li>阻塞问题：需求分析中的 openQuestions 为空</li>
 * </ol>
 * 校验通过则 {@code passed=true}，方案置为 READY_FOR_REVIEW；
 * 否则 {@code passed=false}，方案置为 NEEDS_INPUT，错误记录在 {@code errors} 中。</p>
 */
@Data
@NoArgsConstructor
public class SolutionVerificationResult {

    /** 是否通过校验 */
    private boolean passed;

    /**
     * 校验状态：READY_FOR_REVIEW（通过）/ NEEDS_INPUT（不通过）
     */
    private String status;

    /** 校验错误信息列表（不通过时非空） */
    private List<VerificationError> errors = new ArrayList<>();

    /**
     * 校验错误项 — 携带类型化错误码，便于前端按类型展示。
     */
    @Data
    @NoArgsConstructor
    public static class VerificationError {
        /** 错误码：FILE_NOT_FOUND / SYMBOL_NOT_FOUND / HIGH_RISK_UNCOVERED / TEST_MISSING / EVIDENCE_INVALID / BLOCKING_QUESTIONS */
        private String code;
        /** 人类可读的错误描述 */
        private String message;

        public VerificationError(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
