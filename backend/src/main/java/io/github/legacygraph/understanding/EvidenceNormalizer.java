package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.understanding.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 证据归一化器 —— 将不同工具的输出统一归一化为 ToolEvidence 和 KnowledgeClaimDraft。
 *
 * <p>核心规则（参见方案第 3.2 节）：
 * <ul>
 *   <li>新鲜 MCP 索引返回的符号/文件/调用边 → CODE_GRAPH / CODE，高置信</li>
 *   <li>CLI 读取到的原文片段 → CODE / DOC，确定性证据</li>
 *   <li>CLI Agent 或 LLM 生成的自然语言摘要 → TOOL_SUMMARY / AI_INFERENCE，PENDING_CONFIRM</li>
 *   <li>索引过期、工具降级 → STALE_REFERENCE，PENDING_CONFIRM</li>
 *   <li>外部工具自身判断的"业务规则" → AI_INFERENCE，必须人工复核</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceNormalizer {

    private final SecretScanService secretScanService;

    /**
     * 归一化工具结果，返回证据记录列表（用于落库）和 Claim draft 列表（用于 KnowledgeClaimService）。
     */
    public NormalizationResult normalize(ToolResult result, String projectId, String versionId, String toolRunId) {
        List<Map<String, Object>> evidenceRecords = new ArrayList<>();
        List<KnowledgeClaimDraft> claimDrafts = new ArrayList<>();

        if (result.getEvidenceRecords() == null || result.getEvidenceRecords().isEmpty()) {
            log.debug("工具 {} 未返回证据记录", result.getToolName());
            return new NormalizationResult(evidenceRecords, claimDrafts);
        }

        for (Map<String, Object> raw : result.getEvidenceRecords()) {
            // 脱敏处理
            String excerpt = (String) raw.get("excerpt");
            if (excerpt != null) {
                SecretScanService.SecretScanResult scanResult = secretScanService.scan(excerpt);
                raw.put("excerpt", scanResult.getRedacted());
                raw.put("privacyLevel", scanResult.getSuggestedLevel().name());
            }

            // 根据工具状态和证据类型确定来源类型
            String evidenceType = (String) raw.getOrDefault("evidenceType", "SOURCE_SNIPPET");
            String sourceType = resolveSourceType(evidenceType, result);
            String claimStatus = resolveClaimStatus(sourceType, result);

            evidenceRecords.add(raw);

            // 生成 Claim draft（仅对确定性证据或高置信代码证据）
            if (shouldCreateClaim(evidenceType, sourceType)) {
                KnowledgeClaimDraft draft = KnowledgeClaimDraftMapper.toClaimDraft(
                        raw, sourceType, claimStatus, projectId, versionId, toolRunId
                );
                if (draft != null) {
                    claimDrafts.add(draft);
                }
            }
        }

        log.info("证据归一化完成: tool={}, evidenceCount={}, claimDraftCount={}",
                result.getToolName(), evidenceRecords.size(), claimDrafts.size());
        return new NormalizationResult(evidenceRecords, claimDrafts);
    }

    /**
     * 根据证据类型和工具状态解析 sourceType。
     */
    private String resolveSourceType(String evidenceType, ToolResult result) {
        boolean isStale = "STALE".equals(result.getIndexFreshness());

        return switch (evidenceType) {
            case "SYMBOL", "CALL_PATH" -> isStale ? "STALE_REFERENCE" : "CODE_GRAPH";
            case "SOURCE_SNIPPET" -> isStale ? "STALE_REFERENCE" : "CODE";
            case "DOC_CHUNK" -> "DOC";
            case "SUMMARY" -> "TOOL_SUMMARY";
            case "REPO_MAP" -> "CODE";
            default -> "CODE";
        };
    }

    /**
     * 根据 sourceType 确定 Claim 状态。
     */
    private String resolveClaimStatus(String sourceType, ToolResult result) {
        // 只有 CODE / CODE_GRAPH / DOC / DB / RUNTIME / TEST 可以 CONFIRMED
        if (List.of("CODE", "CODE_GRAPH", "DOC").contains(sourceType)
                && !"STALE".equals(result.getIndexFreshness())) {
            return "CONFIRMED";
        }
        return "PENDING_CONFIRM";
    }

    /**
     * 判断是否应该为该证据创建 Claim。
     */
    private boolean shouldCreateClaim(String evidenceType, String sourceType) {
        // SUMMARY 和业务推断不自动创建 Claim
        if ("SUMMARY".equals(evidenceType) || "TOOL_SUMMARY".equals(sourceType)) {
            return false;
        }
        // 有 sourcePath 和 symbolQn 的证据才创建
        return true;
    }

    /**
     * 归一化结果 DTO。
     */
    public record NormalizationResult(
            List<Map<String, Object>> evidenceRecords,
            List<KnowledgeClaimDraft> claimDrafts
    ) {}
}
