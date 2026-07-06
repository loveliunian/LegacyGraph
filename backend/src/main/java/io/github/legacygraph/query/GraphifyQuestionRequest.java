package io.github.legacygraph.query;

import java.util.List;
import java.util.Set;

/**
 * Agent/RAG 查询请求
 */
public record GraphifyQuestionRequest(
    String projectId,           // 项目ID
    String question,            // 问题文本
    List<String> allowedSourceTypes,  // 允许的来源类型（如 GRAPHIFY_AST, EXTRACTED）
    int maxEvidence,            // 最大证据数量（1-20）
    Set<String> callerRoles     // 调用方角色集合
) {
    public GraphifyQuestionRequest {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (allowedSourceTypes == null) {
            allowedSourceTypes = List.of();
        }
        if (maxEvidence < 1 || maxEvidence > 20) {
            throw new IllegalArgumentException("maxEvidence 必须在 1-20 之间");
        }
        if (callerRoles == null) {
            callerRoles = Set.of();
        }
    }
}
