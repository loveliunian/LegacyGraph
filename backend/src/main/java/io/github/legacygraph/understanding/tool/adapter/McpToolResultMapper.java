package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.ToolCapability;
import io.github.legacygraph.understanding.tool.ToolKind;
import io.github.legacygraph.understanding.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具结果映射器 —— 将 MCP 原始输出映射为归一化的 {@link ToolResult}。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>从 MCP 返回的 map 中提取标准字段（source_path、symbol_qn、line_start、line_end、excerpt）</li>
 *   <li>用 SHA-256 计算 stdout 哈希，用于结果幂等校验</li>
 *   <li>构建 evidenceRecords 列表供下游消费</li>
 * </ul>
 *
 * @author LegacyGraph
 */
public final class McpToolResultMapper {

    /** MCP 工具名称常量 */
    private static final String TOOL_NAME = "codebase-memory-mcp";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final int EXCERPT_MAX_LENGTH = 2000;

    private McpToolResultMapper() {
        // 工具类禁止实例化
    }

    /**
     * 将 MCP searchGraph 返回的原始结果映射为 ToolResult。
     *
     * @param rawResult  MCP 返回的原始 map（期望包含 "results" 列表）
     * @param capability 操作类型
     * @param elapsedMs  耗时毫秒
     * @return 归一化的 ToolResult
     */
    public static ToolResult fromSearchGraph(Map<String, Object> rawResult, ToolCapability capability, long elapsedMs) {
        List<Map<String, Object>> records = extractRecords(rawResult);
        return buildResult(capability, records, elapsedMs, null);
    }

    /**
     * 将 MCP getCodeSnippet 返回的原始结果映射为 ToolResult。
     *
     * @param rawResult  MCP 返回的原始 map（包含 snippet 相关字段）
     * @param capability 操作类型
     * @param elapsedMs  耗时毫秒
     * @return 归一化的 ToolResult
     */
    public static ToolResult fromCodeSnippet(Map<String, Object> rawResult, ToolCapability capability, long elapsedMs) {
        // 单条 snippet 也包装为 evidenceRecords
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> record = new HashMap<>();
        record.put("source_path", rawResult.getOrDefault("source_path", rawResult.get("filePath")));
        record.put("symbol_qn", rawResult.getOrDefault("symbol_qn", rawResult.get("filePath")));
        record.put("line_start", rawResult.getOrDefault("line_start", rawResult.get("lineStart")));
        record.put("line_end", rawResult.getOrDefault("line_end", rawResult.get("lineEnd")));
        record.put("excerpt", rawResult.getOrDefault("excerpt", rawResult.get("content")));
        records.add(record);

        return buildResult(capability, records, elapsedMs, null);
    }

    /**
     * 将 MCP queryGraph 返回的原始结果映射为 ToolResult。
     *
     * @param rawResult  MCP 返回的原始 map（Cypher 查询结果）
     * @param capability 操作类型
     * @param elapsedMs  耗时毫秒
     * @return 归一化的 ToolResult
     */
    public static ToolResult fromQueryGraph(Map<String, Object> rawResult, ToolCapability capability, long elapsedMs) {
        List<Map<String, Object>> records = extractRecords(rawResult);
        return buildResult(capability, records, elapsedMs, null);
    }

    /**
     * 当 MCP 调用失败时，构建失败状态的 ToolResult。
     *
     * @param capability  操作类型（可为 null）
     * @param errorMessage 错误信息
     * @param elapsedMs   耗时毫秒
     * @return 失败状态的 ToolResult
     */
    public static ToolResult fromError(ToolCapability capability, String errorMessage, long elapsedMs) {
        return ToolResult.builder()
                .toolName(TOOL_NAME)
                .toolKind(ToolKind.MCP)
                .operation(capability)
                .status("FAILED")
                .exitCode(-1)
                .elapsedMs(elapsedMs)
                .errorExcerpt(truncate(errorMessage, EXCERPT_MAX_LENGTH))
                .evidenceRecords(List.of())
                .build();
    }

    // ──────────────────────────── 内部辅助方法 ────────────────────────────

    /**
     * 从原始 map 中提取 records 列表。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractRecords(Map<String, Object> rawResult) {
        if (rawResult == null) {
            return List.of();
        }
        Object resultsObj = rawResult.get("results");
        if (resultsObj instanceof List<?> list) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedMap = (Map<String, Object>) itemMap;
                    records.add(normalizeRecord(typedMap));
                }
            }
            return records;
        }
        return List.of();
    }

    /**
     * 将单条记录归一化，只保留指定字段。
     */
    private static Map<String, Object> normalizeRecord(Map<String, Object> raw) {
        Map<String, Object> normalized = new HashMap<>();
        normalized.put("source_path", raw.getOrDefault("source_path", raw.get("filePath")));
        normalized.put("symbol_qn", raw.getOrDefault("symbol_qn", raw.get("name")));
        normalized.put("line_start", raw.getOrDefault("line_start", raw.get("lineStart")));
        normalized.put("line_end", raw.getOrDefault("line_end", raw.get("lineEnd")));
        normalized.put("excerpt", raw.getOrDefault("excerpt", raw.get("content")));
        return normalized;
    }

    /**
     * 构建 ToolResult。
     */
    private static ToolResult buildResult(ToolCapability capability, List<Map<String, Object>> records, long elapsedMs, String indexFreshness) {
        // 将所有 excerpt 拼接成 stdout 字符串，用于 SHA-256 计算
        String stdout = buildStdout(records);
        String sha256 = sha256Hex(stdout);
        String excerpt = truncate(stdout, EXCERPT_MAX_LENGTH);

        return ToolResult.builder()
                .toolName(TOOL_NAME)
                .toolKind(ToolKind.MCP)
                .operation(capability)
                .status(STATUS_SUCCESS)
                .exitCode(0)
                .elapsedMs(elapsedMs)
                .indexFreshness(indexFreshness)
                .stdoutSha256(sha256)
                .stdoutExcerpt(excerpt)
                .evidenceRecords(records)
                .build();
    }

    /**
     * 将所有 evidenceRecords 中的 excerpt 字段拼接为纯文本 stdout。
     */
    private static String buildStdout(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> record : records) {
            Object excerpt = record.get("excerpt");
            if (excerpt != null) {
                sb.append(excerpt.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 计算字符串的 SHA-256 哈希值（十六进制小写）。
     */
    public static String sha256Hex(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 截断字符串到指定最大长度。
     */
    private static String truncate(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
}
