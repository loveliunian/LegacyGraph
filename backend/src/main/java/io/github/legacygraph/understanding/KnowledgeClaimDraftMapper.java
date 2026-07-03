package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 知识断言草稿映射器 —— 将外部工具证据记录映射为 KnowledgeClaimDraft。
 *
 * <p>映射规则（参见方案第 3.2 节）：
 * <ul>
 *   <li>SOURCE_SNIPPET → CODE Claim，高置信（有 hash/行号）</li>
 *   <li>SUMMARY → 不生成 Claim（只能作为 AI_INFERENCE）</li>
 *   <li>STALE 工具结果 → STALE_REFERENCE Claim，PENDING_CONFIRM</li>
 * </ul>
 */
@Slf4j
public final class KnowledgeClaimDraftMapper {

    private KnowledgeClaimDraftMapper() {
        // 工具类
    }

    /**
     * 将工具证据记录映射为 KnowledgeClaimDraft。
     *
     * @param raw        原始证据记录
     * @param sourceType 来源类型
     * @param claimStatus Claim 状态
     * @param projectId  项目 ID
     * @param versionId  版本 ID
     * @param toolRunId  工具运行 ID
     * @return KnowledgeClaimDraft，无法映射时返回 null
     */
    public static KnowledgeClaimDraft toClaimDraft(
            Map<String, Object> raw,
            String sourceType,
            String claimStatus,
            String projectId,
            String versionId,
            String toolRunId) {

        String evidenceType = (String) raw.getOrDefault("evidenceType", "SOURCE_SNIPPET");
        String symbolQn = (String) raw.get("symbolQn");
        String sourcePath = (String) raw.get("sourcePath");
        String excerpt = (String) raw.get("excerpt");

        if (symbolQn == null && sourcePath == null) {
            log.debug("证据缺少 symbolQn 和 sourcePath，跳过 Claim 映射");
            return null;
        }

        // 确定 subjectType
        String subjectType = resolveSubjectType(evidenceType, symbolQn);
        String subjectKey = symbolQn != null ? symbolQn : sourcePath;

        // 确定谓词
        String predicate = resolvePredicate(evidenceType);

        Double confidence = raw.get("confidence") instanceof Number n
                ? n.doubleValue() : 0.85;

        KnowledgeClaimDraft draft = KnowledgeClaimDraft.builder()
                .projectId(projectId)
                .versionId(versionId)
                .subjectType(subjectType)
                .subjectKey(subjectKey)
                .predicate(predicate)
                .objectType(null)
                .objectKey(null)
                .objectValue(excerpt)
                .qualifiers(Map.of(
                        "sourcePath", sourcePath != null ? sourcePath : "",
                        "evidenceType", evidenceType,
                        "toolRunId", toolRunId
                ))
                .evidenceIds(List.of(toolRunId))
                .sourceType(sourceType)
                .extractor("CodeUnderstandingTool")
                .confidence(BigDecimal.valueOf(confidence))
                .build();

        log.debug("映射 Claim draft: {}/{}/{}/{}", subjectType, subjectKey, predicate, sourceType);
        return draft;
    }

    /**
     * 根据证据类型和符号名解析主体类型。
     */
    private static String resolveSubjectType(String evidenceType, String symbolQn) {
        if (symbolQn != null) {
            // 根据 qualified name 判断类型
            String lower = symbolQn.toLowerCase();
            if (lower.contains("controller")) return "ApiEndpoint";
            if (lower.contains("service") || lower.contains("impl")) return "Service";
            if (lower.contains("mapper") || lower.contains("repository") || lower.contains("dao")) return "DataAccess";
            if (lower.contains("entity") || lower.contains("model") || lower.contains("dto")) return "Model";
            if (lower.contains("config") || lower.contains("property")) return "Config";
        }
        return switch (evidenceType) {
            case "SYMBOL" -> "Method";
            case "CALL_PATH" -> "CallPath";
            case "SOURCE_SNIPPET" -> "CodeSnippet";
            case "DOC_CHUNK" -> "Document";
            case "REPO_MAP" -> "Module";
            default -> "CodeElement";
        };
    }

    /**
     * 根据证据类型解析谓词。
     */
    private static String resolvePredicate(String evidenceType) {
        return switch (evidenceType) {
            case "SYMBOL" -> "DEFINED_AS";
            case "CALL_PATH" -> "CALLS";
            case "SOURCE_SNIPPET" -> "CONTAINS";
            case "DOC_CHUNK" -> "DESCRIBES";
            case "REPO_MAP" -> "STRUCTURED_AS";
            default -> "RELATED_TO";
        };
    }
}
