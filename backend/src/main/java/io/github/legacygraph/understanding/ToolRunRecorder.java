package io.github.legacygraph.understanding;

import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import io.github.legacygraph.repository.ToolRunRepository;
import io.github.legacygraph.understanding.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 工具运行记录器 —— 记录每次工具调用的运行元数据和证据到数据库。
 *
 * <p>原则：stdout 不全量保存，只保存 hash 和截断摘要。敏感内容由 SecretScanService 脱敏后入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRunRecorder {

    private final ToolRunRepository toolRunRepository;
    private final ToolEvidenceRepository toolEvidenceRepository;

    /** 摘要截断长度 */
    private static final int EXCERPT_MAX_LENGTH = 2000;

    /**
     * 记录工具运行开始（状态 PENDING）。
     */
    @Transactional
    public ToolRunEntity recordRunStart(String projectId, String versionId,
                                         String toolName, String toolKind, String operation,
                                         String queryHash) {
        ToolRunEntity run = new ToolRunEntity();
        run.setProjectId(projectId);
        run.setVersionId(versionId);
        run.setToolName(toolName);
        run.setToolKind(toolKind);
        run.setOperation(operation);
        run.setQueryHash(queryHash);
        run.setStatus("PENDING");
        run.setCreatedAt(LocalDateTime.now());
        toolRunRepository.insert(run);
        log.debug("工具运行记录开始: runId={}, tool={}, operation={}", run.getId(), toolName, operation);
        return run;
    }

    /**
     * 记录工具运行完成（从 ToolResult 更新状态）。
     */
    @Transactional
    public void recordRunComplete(String runId, ToolResult result) {
        ToolRunEntity run = new ToolRunEntity();
        run.setId(runId);
        run.setStatus(result.getStatus());
        run.setExitCode(result.getExitCode());
        run.setElapsedMs(result.getElapsedMs());
        run.setIndexFreshness(result.getIndexFreshness());
        run.setStdoutSha256(result.getStdoutSha256());
        run.setStdoutExcerpt(truncate(result.getStdoutExcerpt()));
        run.setErrorExcerpt(truncate(result.getErrorExcerpt()));
        toolRunRepository.updateById(run);
        log.debug("工具运行记录完成: runId={}, status={}, elapsedMs={}", runId, result.getStatus(), result.getElapsedMs());
    }

    /**
     * 记录工具证据。
     */
    @Transactional
    public void recordEvidence(String runId, List<Map<String, Object>> evidenceRecords) {
        if (evidenceRecords == null || evidenceRecords.isEmpty()) {
            return;
        }
        for (Map<String, Object> record : evidenceRecords) {
            ToolEvidenceEntity ev = new ToolEvidenceEntity();
            ev.setToolRunId(runId);
            ev.setEvidenceType((String) record.getOrDefault("evidenceType", "SOURCE_SNIPPET"));
            ev.setSourcePath((String) record.get("sourcePath"));
            ev.setSymbolQn((String) record.get("symbolQn"));
            ev.setLineStart((Integer) record.get("lineStart"));
            ev.setLineEnd((Integer) record.get("lineEnd"));
            ev.setExcerpt(truncate((String) record.get("excerpt")));
            ev.setContentSha256(sha256((String) record.get("excerpt")));
            ev.setGraphNodeKey((String) record.get("graphNodeKey"));
            ev.setConfidence(toDouble(record.get("confidence")));
            ev.setPrivacyLevel((String) record.getOrDefault("privacyLevel", "INTERNAL"));
            ev.setCreatedAt(LocalDateTime.now());
            toolEvidenceRepository.insert(ev);
        }
        log.debug("工具证据记录完成: runId={}, count={}", runId, evidenceRecords.size());
    }

    /**
     * 计算字符串 SHA-256。
     */
    public static String sha256(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() <= EXCERPT_MAX_LENGTH ? text : text.substring(0, EXCERPT_MAX_LENGTH) + "...[截断]";
    }

    private Double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.5; }
        }
        return 0.5;
    }
}
