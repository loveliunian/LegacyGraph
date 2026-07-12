package io.github.legacygraph.task.step;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ProcessFitness;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.processmining.Pm4PyClient;
import io.github.legacygraph.repository.ProcessFitnessRepository;
import io.github.legacygraph.repository.RuntimeTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI_PROCESS_MINING — 扫描后流程挖掘一致性校验（H25）。
 *
 * <p>读取 BusinessProcess 节点构建流程模型 JSON，读取 lg_runtime_trace 的 Operation 序列构建用例 CSV，
 * 调用 {@link Pm4PyClient#runConformance} 执行 conformance checking，结果写入 {@code lg_process_fitness} 表。</p>
 *
 * <p>降级策略：PM4Py 不可用时跳过 + 日志，不阻塞扫描。属于 ENHANCE 状态机后的 PROCESS_MINING 步骤。</p>
 */
@Slf4j
@Component
public class ProcessMiningStep implements AiScanStepExecutor {

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final RuntimeTraceRepository runtimeTraceRepository;
    private final ProcessFitnessRepository processFitnessRepository;
    private final Pm4PyClient pm4PyClient;

    public ProcessMiningStep(AiScanStepSupport support,
                             Neo4jGraphDao neo4jGraphDao,
                             RuntimeTraceRepository runtimeTraceRepository,
                             ProcessFitnessRepository processFitnessRepository,
                             @Autowired(required = false) Pm4PyClient pm4PyClient) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.processFitnessRepository = processFitnessRepository;
        this.pm4PyClient = pm4PyClient;
    }

    @Override
    public String getStepName() {
        return "AI_PROCESS_MINING";
    }

    @Override
    public int getOrder() {
        return 9; // 在 UnderstandingEnhancementStep(8) 之后
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.PROCESS_MINING;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();

        ScanTask task = support.createTask(projectId, versionId, "AI_PROCESS_MINING", "流程挖掘一致性校验");

        // 降级：PM4Py 不可用
        if (pm4PyClient == null || !pm4PyClient.isAvailable()) {
            String msg = "PM4Py 不可用，跳过流程挖掘一致性校验";
            log.info("ProcessMining skipped: pm4py not available, projectId={}, versionId={}", projectId, versionId);
            support.completeTask(task, msg, null);
            return StepExecutionResult.builder().success(true).message(msg).build();
        }

        try {
            // 1. 查询 BusinessProcess 节点
            List<GraphNode> processNodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.BusinessProcess.name(), null, null, null, 0);
            if (processNodes.isEmpty()) {
                String msg = "无 BusinessProcess 节点，跳过流程挖掘";
                support.completeTask(task, msg, null);
                return StepExecutionResult.builder().success(true).message(msg).build();
            }

            // 2. 查询 RuntimeTrace（按 traceId 分组）
            List<RuntimeTrace> traces = runtimeTraceRepository.lambdaQuery()
                    .eq(RuntimeTrace::getProjectId, projectId)
                    .eq(RuntimeTrace::getVersionId, versionId)
                    .list();
            if (traces.isEmpty()) {
                String msg = "无 RuntimeTrace 数据，跳过流程挖掘";
                support.completeTask(task, msg, null);
                return StepExecutionResult.builder().success(true).message(msg).build();
            }

            // 3. 写临时文件
            Path tempDir = Files.createTempDirectory("pm4py-conformance-");
            Path modelJson = tempDir.resolve("process_model.json");
            Path casesCsv = tempDir.resolve("cases.csv");

            writeProcessModelJson(modelJson, processNodes);
            writeCasesCsv(casesCsv, traces);

            // 4. 调用 PM4Py
            Pm4PyClient.ConformanceResult result = pm4PyClient.runConformance(
                    casesCsv.toString(), modelJson.toString());

            if (!result.isAvailable()) {
                String msg = "PM4Py conformance 不可用: " +
                        (result.getErrorMessage() != null ? result.getErrorMessage() : "unknown");
                log.warn("ProcessMining conformance unavailable: {}", msg);
                support.completeTask(task, msg, null);
                return StepExecutionResult.builder().success(true).message(msg).build();
            }

            // 5. 写入 lg_process_fitness 表（每个 BusinessProcess 一行）
            int savedCount = 0;
            for (GraphNode processNode : processNodes) {
                try {
                    ProcessFitness pf = new ProcessFitness();
                    pf.setProjectId(projectId);
                    pf.setVersionId(versionId);
                    pf.setProcessId(parseProcessId(processNode));
                    pf.setFitness(BigDecimal.valueOf(result.getFitness()));
                    pf.setPrecision(BigDecimal.valueOf(result.getPrecision()));
                    pf.setGeneralization(BigDecimal.valueOf(result.getGeneralization()));
                    pf.setCalculatedAt(LocalDateTime.now());
                    processFitnessRepository.insert(pf);
                    savedCount++;
                } catch (Exception e) {
                    log.warn("Failed to save ProcessFitness for process {}: {}",
                            processNode.getNodeKey(), e.getMessage());
                }
            }

            String summary = String.format(
                    "流程挖掘完成: %d 个 BusinessProcess, fitness=%.4f, precision=%.4f, generalization=%.4f",
                    savedCount, result.getFitness(), result.getPrecision(), result.getGeneralization());
            support.completeTask(task, summary, null);
            log.info("ProcessMining completed: projectId={}, versionId={}, {}",
                    projectId, versionId, summary);
            return StepExecutionResult.builder().success(true)
                    .message(summary)
                    .processedCount(savedCount)
                    .build();
        } catch (Exception e) {
            log.error("ProcessMining failed: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage(), e);
            support.completeTask(task, null, "流程挖掘失败（不影响基础扫描）: " + e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * 写流程模型 JSON（简化格式：节点列表 + 顺序关系）。
     */
    private void writeProcessModelJson(Path path, List<GraphNode> processNodes) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"processes\":[");
        for (int i = 0; i < processNodes.size(); i++) {
            GraphNode n = processNodes.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(n.getNodeKey() != null ? n.getNodeKey() : n.getId())
                    .append("\",\"name\":\"")
                    .append(escapeJson(n.getNodeName() != null ? n.getNodeName() : ""))
                    .append("\"}");
        }
        sb.append("]}");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 写用例 CSV（case_id, activity, timestamp）。
     */
    private void writeCasesCsv(Path path, List<RuntimeTrace> traces) throws Exception {
        // 按 traceId 分组
        Map<String, List<RuntimeTrace>> byTrace = traces.stream()
                .filter(t -> t.getTraceId() != null && t.getOperationName() != null)
                .collect(Collectors.groupingBy(RuntimeTrace::getTraceId));

        StringBuilder sb = new StringBuilder();
        sb.append("case_id,activity,timestamp\n");
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        for (var entry : byTrace.entrySet()) {
            String caseId = entry.getKey();
            List<RuntimeTrace> spans = new ArrayList<>(entry.getValue());
            // 按时间排序
            spans.sort((a, b) -> {
                if (a.getStartedAt() == null && b.getStartedAt() == null) return 0;
                if (a.getStartedAt() == null) return 1;
                if (b.getStartedAt() == null) return -1;
                return a.getStartedAt().compareTo(b.getStartedAt());
            });
            for (RuntimeTrace span : spans) {
                sb.append(caseId).append(",")
                        .append(escapeCsv(span.getOperationName())).append(",")
                        .append(span.getStartedAt() != null ? span.getStartedAt().format(fmt) : "")
                        .append("\n");
            }
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private Long parseProcessId(GraphNode node) {
        // 尝试从 nodeKey 解析数字 ID，失败则用 hashCode
        if (node.getNodeKey() != null) {
            try {
                return Long.parseLong(node.getNodeKey().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return (long) (node.getId() != null ? node.getId().hashCode() : 0);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
