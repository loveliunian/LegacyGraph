package io.github.legacygraph.processmining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PM4Py 一致性校验客户端（评估 §6.2 G15）。
 *
 * <p>通过子进程调用 {@code pm4py_conformance.py} 进行流程一致性分析。
 * 协议：</p>
 * <ol>
 *   <li>Java 端把 processMiningCases (CSV) 与 processModel (JSON) 写到临时目录</li>
 *   <li>通过命令行参数把路径传入 Python 脚本</li>
 *   <li>Python 输出 JSON 到 stdout，Java 解析后写回 Neo4j</li>
 * </ol>
 *
 * <p><b>降级策略</b>：当 {@code python3} 不在 PATH 或 {@code pm4py} 包未安装，
 * 返回 {@code available=false} + 详细错误信息，不抛异常。</p>
 *
 * <p>性能预算：单次 conformance 在 50 traces 内 < 30s（设置 60s 硬超时）。</p>
 */
@Slf4j
@Service
public class Pm4PyClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${legacygraph.pm4py.python-path:python3}")
    private String pythonPath;

    @Value("${legacygraph.pm4py.script-path:scripts/pm4py_conformance.py}")
    private String scriptPath;

    @Value("${legacygraph.pm4py.timeout-seconds:60}")
    private int timeoutSeconds;

    /**
     * 是否可用：python3 + pm4py 都已安装。
     */
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(pythonPath, "-c", "import pm4py; print(pm4py.__version__)")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = r.readLine();
            }
            int exit = p.exitValue();
            return exit == 0 && output != null && output.matches("\\d+\\.\\d+\\.\\d+.*");
        } catch (Exception e) {
            log.debug("PM4Py availability check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行 conformance 检查。
     *
     * @param casesCsvPath 用例 CSV（含 case_id, activity, timestamp）
     * @param modelJsonPath 流程模型 JSON（start_activity, end_activity, transitions）
     * @return conformance 结果
     */
    public ConformanceResult runConformance(String casesCsvPath, String modelJsonPath) {
        long start = System.currentTimeMillis();
        Path script = Path.of(scriptPath);
        if (!Files.exists(script)) {
            log.warn("pm4py_conformance.py not found at {}", scriptPath);
            return ConformanceResult.builder()
                    .available(false)
                    .errorMessage("Script not found: " + scriptPath)
                    .build();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, script.toString(),
                    "--cases", casesCsvPath,
                    "--model", modelJsonPath);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = readerThread(process.getInputStream(), stdout);
            Thread errReader = readerThread(process.getErrorStream(), stderr);
            outReader.start();
            errReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ConformanceResult.builder()
                        .available(true)
                        .errorMessage("PM4Py timed out after " + timeoutSeconds + "s")
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }
            outReader.join(1000);
            errReader.join(1000);

            int exit = process.exitValue();
            String stderrStr = stderr.toString();
            if (exit != 0) {
                log.warn("PM4Py exited with {}: {}", exit, stderrStr);
                return ConformanceResult.builder()
                        .available(true)
                        .errorMessage("Non-zero exit (" + exit + "): " + stderrStr)
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            // 解析 stdout JSON
            JsonNode result = objectMapper.readTree(stdout.toString());
            return ConformanceResult.builder()
                    .available(true)
                    .fitness(result.path("fitness").asDouble(0.0))
                    .precision(result.path("precision").asDouble(0.0))
                    .generalization(result.path("generalization").asDouble(0.0))
                    .fitnessPerTrace(toList(result.path("fitnessPerTrace")))
                    .deviations(toList(result.path("deviations")))
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.warn("PM4Py execution failed: {}", e.getMessage());
            return ConformanceResult.builder()
                    .available(true)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private static Thread readerThread(java.io.InputStream in, StringBuilder sb) {
        return new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) { sb.append(line).append('\n'); }
                }
            } catch (Exception ignore) { }
        });
    }

    private static List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) out.add(item.asText());
        return out;
    }

    @lombok.Data
    @lombok.Builder
    public static class ConformanceResult {
        private boolean available;
        private double fitness;
        private double precision;
        private double generalization;
        private List<String> fitnessPerTrace;
        private List<String> deviations;
        private long durationMs;
        private String errorMessage;
    }
}