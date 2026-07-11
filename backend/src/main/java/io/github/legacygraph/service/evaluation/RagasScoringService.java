package io.github.legacygraph.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.evaluation.RagasReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * P3-2: Ragas 评分服务 — 通过 Python 子进程调用 ragas 库进行 RAG 质量评估。
 * <p>
 * 仅在 {@code legacygraph.evaluation.ragas.python-enabled=true} 时注入，
 * 替代 {@link RagasMetricsService} 的简化版实现。
 * </p>
 * <p>
 * 调用流程：
 * <ol>
 *   <li>将问答数据写入临时 JSONL 文件</li>
 *   <li>执行 {@code ragas evaluate --dataset cases.jsonl --metrics context_precision,context_recall,faithfulness,answer_relevancy}</li>
 *   <li>解析 JSON 输出，构建 {@link RagasReport}</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "legacygraph.evaluation.ragas.python-enabled", havingValue = "true")
public class RagasScoringService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${legacygraph.evaluation.ragas.python-executable:python3}")
    private String pythonExecutable;

    @Value("${legacygraph.evaluation.ragas.script-path:scripts/ragas_evaluate.py}")
    private String scriptPath;

    @Value("${legacygraph.evaluation.ragas.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${legacygraph.evaluation.ragas.llm-model:gpt-4o-mini}")
    private String llmModel;

    /**
     * 批量评估问答对，返回平均 Ragas 指标。
     *
     * @param testCases 问答测试用例列表，每个 Map 包含：
     *                  question, answer, contexts (List<String>), expectedEntities (List<String>)
     * @return 平均指标报告
     */
    public RagasReport evaluateBatch(List<Map<String, Object>> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return RagasReport.builder()
                    .contextPrecision(0).contextRecall(0)
                    .faithfulness(0).answerRelevancy(0)
                    .details(Map.of("error", "empty test cases"))
                    .build();
        }

        try {
            // 1. 写入临时 JSONL
            Path tempFile = Files.createTempFile("ragas-dataset-", ".jsonl");
            for (Map<String, Object> tc : testCases) {
                String json = objectMapper.writeValueAsString(tc);
                Files.writeString(tempFile, json + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            }

            // 2. 执行 ragas evaluate
            List<String> command = new ArrayList<>(List.of(
                    pythonExecutable, scriptPath,
                    "--dataset", tempFile.toString(),
                    "--metrics", "context_precision,context_recall,faithfulness,answer_relevancy",
                    "--llm-model", llmModel
            ));

            log.info("Ragas evaluate: {}", String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("Ragas evaluate timed out after {}s", timeoutSeconds);
                return fallbackReport("timeout after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Ragas evaluate failed (exit={}): {}", exitCode, output);
                return fallbackReport("exit code " + exitCode + ": " + output);
            }

            // 3. 解析输出 JSON
            return parseRagasOutput(output, testCases.size());

        } catch (Exception e) {
            log.error("Ragas evaluate error: {}", e.getMessage(), e);
            return fallbackReport(e.getMessage());
        }
    }

    /**
     * 解析 ragas evaluate 的 JSON 输出。
     * <p>
     * 预期格式：
     * {@code
     * {"context_precision": 0.85, "context_recall": 0.90, "faithfulness": 0.88, "answer_relevancy": 0.92}
     * }
     * </p>
     */
    @SuppressWarnings("unchecked")
    private RagasReport parseRagasOutput(String output, int caseCount) {
        try {
            // 尝试提取最后一行 JSON
            String[] lines = output.trim().split("\n");
            String jsonLine = lines[lines.length - 1].trim();
            Map<String, Object> result = objectMapper.readValue(jsonLine, Map.class);

            Map<String, String> details = new HashMap<>();
            details.put("caseCount", String.valueOf(caseCount));
            details.put("rawOutput", output.length() > 500 ? output.substring(0, 500) : output);

            return RagasReport.builder()
                    .contextPrecision(toDouble(result.get("context_precision")))
                    .contextRecall(toDouble(result.get("context_recall")))
                    .faithfulness(toDouble(result.get("faithfulness")))
                    .answerRelevancy(toDouble(result.get("answer_relevancy")))
                    .details(details)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse ragas output: {}", e.getMessage());
            return fallbackReport("parse error: " + e.getMessage());
        }
    }

    private RagasReport fallbackReport(String error) {
        Map<String, String> details = new HashMap<>();
        details.put("error", error);
        return RagasReport.builder()
                .contextPrecision(0).contextRecall(0)
                .faithfulness(0).answerRelevancy(0)
                .details(details)
                .build();
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
        }
        return 0;
    }
}
