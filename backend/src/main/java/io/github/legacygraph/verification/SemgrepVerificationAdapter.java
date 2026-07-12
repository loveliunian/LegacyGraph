package io.github.legacygraph.verification;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.adapter.ScanContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Semgrep 静态分析验证适配器（H24）。
 *
 * <p>通过 Semgrep CLI 扫描源码，发现的调用模式对照本地图谱中的 CALLS / READS_DB / WRITES_DB 边做校验：
 * <ul>
 *   <li>Semgrep 规则命中的调用 → confirmedEdges（置信度 1.0）</li>
 *   <li>本地缺失 → missingEdges（置信度 0.85，PENDING_CONFIRM）</li>
 * </ul>
 *
 * <p>降级策略：Semgrep 不可用时 {@link #checkHealth()} 返回 false，由
 * {@link ExternalVerificationService} 自动跳过，不阻塞扫描。
 *
 * <p>规则集：默认使用内置的 java-spring + sql-injection 规则集，
 * 可通过 {@code legacygraph.external-verification.semgrep.rules} 自定义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemgrepVerificationAdapter implements ExternalVerificationAdapter {

    private static final String ADAPTER_NAME = "semgrep-verification";
    private static final String SOURCE_TOOL = "semgrep";

    private static final double CONFIRMED_CONFIDENCE = 1.0;
    private static final double MISSING_CONFIDENCE = 0.85;

    private final Neo4jGraphDao neo4jGraphDao;

    /** Semgrep 是否启用（默认 false，需显式开启 + 安装 semgrep） */
    @Value("${legacygraph.external-verification.semgrep.enabled:false}")
    private boolean semgrepEnabled;

    /** Semgrep 可执行文件路径（默认 semgrep，期望在 PATH 中） */
    @Value("${legacygraph.external-verification.semgrep.binary:semgrep}")
    private String semgrepBinary;

    /** Semgrep 规则集（默认 p/java + p/sqli） */
    @Value("${legacygraph.external-verification.semgrep.rules:p/java,p/sqli}")
    private String semgrepRules;

    /** Semgrep 扫描超时（秒） */
    @Value("${legacygraph.external-verification.semgrep.timeout-seconds:300}")
    private int semgrepTimeoutSeconds;

    @Override
    public String adapterName() {
        return ADAPTER_NAME;
    }

    @Override
    public int priority() {
        return 40; // 在 Joern(30) 之后
    }

    @Override
    public boolean supports(ScanContext context) {
        return semgrepEnabled && context != null && context.getBackendDir() != null;
    }

    @Override
    public VerificationResult verify(String projectId, String versionId, ScanContext context) {
        VerificationResult empty = VerificationResult.empty(ADAPTER_NAME);
        if (!semgrepEnabled || context == null || context.getBackendDir() == null) {
            return empty;
        }

        try {
            // 1. 执行 Semgrep 扫描
            List<SemgrepFinding> findings = runSemgrepScan(context.getBackendDir());
            if (findings.isEmpty()) {
                log.info("Semgrep 未发现任何调用模式，跳过验证: projectId={}", projectId);
                return empty;
            }

            // 2. 查询本地图谱中的 Service 节点
            List<GraphNode> localServices = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.Service.name(), null, null, null, 0);
            java.util.Set<String> localServiceNames = new java.util.HashSet<>();
            for (GraphNode svc : localServices) {
                if (svc.getNodeName() != null) {
                    localServiceNames.add(svc.getNodeName());
                }
            }

            // 3. 对比
            List<VerifiedEdge> confirmed = new ArrayList<>();
            List<VerifiedEdge> missing = new ArrayList<>();
            for (SemgrepFinding f : findings) {
                VerifiedEdge ve = VerifiedEdge.builder()
                        .fromNodeKey(f.callerClass)
                        .toNodeKey(f.calleeClass)
                        .edgeType(f.edgeType)
                        .sourceTool(SOURCE_TOOL)
                        .build();
                if (localServiceNames.contains(f.calleeClass)) {
                    ve.setConfidence(CONFIRMED_CONFIDENCE);
                    confirmed.add(ve);
                } else {
                    ve.setConfidence(MISSING_CONFIDENCE);
                    missing.add(ve);
                }
            }

            log.info("Semgrep 验证完成: 发现 {} 个模式, 确认 {} 条, 缺失 {} 条",
                    findings.size(), confirmed.size(), missing.size());

            return VerificationResult.builder()
                    .adapterName(ADAPTER_NAME)
                    .confirmedEdges(confirmed)
                    .missingEdges(missing)
                    .suspiciousEdges(new ArrayList<>())
                    .nodeProperties(new ArrayList<>())
                    .totalChecked(findings.size())
                    .totalConfirmed(confirmed.size())
                    .build();
        } catch (Exception e) {
            log.warn("Semgrep 验证异常，降级跳过: {}", e.getMessage());
            return empty;
        }
    }

    @Override
    public boolean checkHealth() {
        if (!semgrepEnabled) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(semgrepBinary, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("Semgrep --version 超时");
                return false;
            }
            boolean healthy = process.exitValue() == 0;
            if (!healthy) {
                log.debug("Semgrep 健康检查失败，exit={}", process.exitValue());
            }
            return healthy;
        } catch (Exception e) {
            log.debug("Semgrep 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行 Semgrep 扫描并解析 JSON 输出。
     *
     * <p>命令：{@code semgrep --config p/java --config p/sqli --json <sourceDir>}
     */
    private List<SemgrepFinding> runSemgrepScan(String sourceDir) throws Exception {
        Path outFile = Paths.get(sourceDir, "semgrep_results.json");

        List<String> cmd = new ArrayList<>();
        cmd.add(semgrepBinary);
        // 拆分规则集
        for (String rule : semgrepRules.split(",")) {
            String trimmed = rule.trim();
            if (!trimmed.isEmpty()) {
                cmd.add("--config");
                cmd.add(trimmed);
            }
        }
        cmd.add("--json");
        cmd.add("--output");
        cmd.add(outFile.toString());
        cmd.add(sourceDir);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 消费 stdout/stderr 避免管道阻塞
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("semgrep: {}", line);
            }
        }

        boolean done = process.waitFor(semgrepTimeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            log.warn("Semgrep 扫描超时（{}s）", semgrepTimeoutSeconds);
            return List.of();
        }
        // semgrep 正常退出码 0（无发现）或 1（有发现），均视为成功
        int exit = process.exitValue();
        if (exit != 0 && exit != 1) {
            log.warn("Semgrep 扫描失败，exit={}", exit);
            return List.of();
        }

        return parseSemgrepJson(outFile);
    }

    private List<SemgrepFinding> parseSemgrepJson(Path jsonFile) {
        List<SemgrepFinding> findings = new ArrayList<>();
        if (!Files.exists(jsonFile)) {
            return findings;
        }
        try {
            String content = Files.readString(jsonFile, StandardCharsets.UTF_8);
            // 简化解析：提取 "results" 数组中的 check_id + path
            // 每个结果形如 {"check_id":"java.lang.security...", "path":".../Foo.java", ...}
            int resultsIdx = content.indexOf("\"results\"");
            if (resultsIdx < 0) return findings;
            int arrayStart = content.indexOf('[', resultsIdx);
            int arrayEnd = content.lastIndexOf(']');
            if (arrayStart < 0 || arrayEnd < 0 || arrayEnd <= arrayStart) return findings;

            String arrayContent = content.substring(arrayStart + 1, arrayEnd);
            // 按 "}{" 分割结果对象
            String[] items = arrayContent.split("\\},\\s*\\{");
            for (String item : items) {
                String cleaned = item;
                if (!cleaned.startsWith("{")) cleaned = "{" + cleaned;
                if (!cleaned.endsWith("}")) cleaned = cleaned + "}";

                String checkId = extractJsonField(cleaned, "check_id");
                String path = extractJsonField(cleaned, "path");
                if (checkId == null || path == null) continue;

                // 从路径推断 caller 类名（简化：取文件名去 .java）
                String callerClass = path;
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0) {
                    callerClass = path.substring(lastSlash + 1).replace(".java", "");
                }

                // 根据 check_id 推断边类型
                String edgeType = "CALLS";
                if (checkId.contains("sql") || checkId.contains("db")) {
                    edgeType = checkId.contains("inject") ? "READS_DB" : "WRITES_DB";
                }

                // callee 类名简化：从 check_id 末尾段取
                String calleeClass = checkId;
                int lastDot = checkId.lastIndexOf('.');
                if (lastDot >= 0) {
                    calleeClass = checkId.substring(lastDot + 1);
                }

                findings.add(new SemgrepFinding(callerClass, calleeClass, edgeType, checkId));
            }
        } catch (Exception e) {
            log.warn("解析 Semgrep JSON 输出失败: {}", e.getMessage());
        }
        return findings;
    }

    private String extractJsonField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) return null;
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** Semgrep 扫描发现项 */
    private record SemgrepFinding(String callerClass, String calleeClass,
                                   String edgeType, String checkId) {}
}
