package io.github.legacygraph.query;

import java.util.List;
import java.util.Set;

/**
 * Agent/RAG 查询响应
 */
public record GraphifyQuestionAnswer(
    String answer,              // 回答文本
    Set<String> evidenceIds,    // 证据ID集合
    List<String> sourcePaths,   // 源码路径（已脱敏）
    double confidence,          // 置信度（0.0-1.0）
    List<String> warnings       // 警告信息
) {
    public GraphifyQuestionAnswer {
        if (answer == null) {
            answer = "";
        }
        if (evidenceIds == null) {
            evidenceIds = Set.of();
        }
        if (sourcePaths == null) {
            sourcePaths = List.of();
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 0.0-1.0 之间");
        }
        if (warnings == null) {
            warnings = List.of();
        }
    }

    /**
     * 创建空问题的标准回答
     */
    public static GraphifyQuestionAnswer emptyQuestion() {
        return new GraphifyQuestionAnswer(
            "Question is empty.",
            Set.of(),
            List.of(),
            0.0,
            List.of()
        );
    }

    /**
     * 创建无证据的低置信度回答
     */
    public static GraphifyQuestionAnswer noEvidenceFound(String reason) {
        return new GraphifyQuestionAnswer(
            "Unable to find sufficient evidence: " + reason,
            Set.of(),
            List.of(),
            0.2,
            List.of("Low confidence due to insufficient evidence")
        );
    }
}
