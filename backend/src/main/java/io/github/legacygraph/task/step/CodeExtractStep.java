package io.github.legacygraph.task.step;

import io.github.legacygraph.agent.CodeFactAgent;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI_CODE_EXTRACT — 从代码抽取业务事实，让"无文档"项目也能产出业务/功能节点。
 *
 * <p>复用 {@link CodeFactAgent} 对 Service/Controller 类源码做 LLM 语义理解，
 * 抽取结果桥接为 {@link DocUnderstandingAgent.BusinessFactExtraction}（填充 features），
 * 复用 {@link BusinessGraphBuilder#buildBusinessGraph} 落图。</p>
 */
@Slf4j
@Component
public class CodeExtractStep implements AiScanStepExecutor {

    /** 代码事实抽取的类节点上限，避免 LLM 调用过多 */
    private static final int MAX_CODE_EXTRACT_NODES = 30;
    /** 单个代码文件读取上限（字符），配合 truncate 控制 prompt 体积 */
    private static final int CODE_CONTENT_LIMIT = 8000;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final CodeFactAgent codeFactAgent;
    private final BusinessGraphBuilder businessGraphBuilder;

    public CodeExtractStep(AiScanStepSupport support,
                           Neo4jGraphDao neo4jGraphDao,
                           CodeFactAgent codeFactAgent,
                           BusinessGraphBuilder businessGraphBuilder) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.codeFactAgent = codeFactAgent;
        this.businessGraphBuilder = businessGraphBuilder;
    }

    @Override
    public String getStepName() {
        return "AI_CODE_EXTRACT";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.PARSE_FILES;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        ScanTask task = support.createTask(projectId, versionId, "AI_CODE_EXTRACT", "代码业务事实抽取");
        try {
            // 取 Service/Controller 类节点作为业务语义最集中的抽取对象
            List<GraphNode> codeNodes = new ArrayList<>();
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Service.name(), null, null, null, MAX_CODE_EXTRACT_NODES));
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Controller.name(), null, null, null, MAX_CODE_EXTRACT_NODES));

            if (codeNodes.isEmpty()) {
                support.completeTask(task, "⚠ 无 Service/Controller 类节点，跳过代码事实抽取", null);
                return StepExecutionResult.builder().success(true)
                        .message("⚠ 无 Service/Controller 类节点，跳过代码事实抽取").build();
            }

            // 按 sourcePath 去重后截断到上限
            Set<String> uniquePaths = new LinkedHashSet<>();
            List<GraphNode> uniqueNodes = new ArrayList<>();
            for (GraphNode node : codeNodes) {
                String path = node.getSourcePath();
                if (path != null && !path.isBlank() && uniquePaths.add(path)) {
                    uniqueNodes.add(node);
                    if (uniqueNodes.size() >= MAX_CODE_EXTRACT_NODES) break;
                }
            }

            // 断点续传：跳过已完成文件
            java.util.Set<String> donePaths = support.findDoneFilePaths(projectId, versionId, "CODE_EXTRACT");
            long skipped = uniqueNodes.stream().filter(n -> donePaths.contains(n.getSourcePath())).count();
            if (skipped > 0) {
                log.info("AI_CODE_EXTRACT checkpoint resume: {} files already done, {} remaining",
                        skipped, uniqueNodes.size() - skipped);
            }

            // 设置总项数用于 ETA 计算
            int totalNodes = (int) (uniqueNodes.size() - skipped);
            support.updateTaskProgress(task, totalNodes, 0, null);

            log.info("AI_CODE_EXTRACT starting: versionId={}, nodeCount={}, dedupedTo={}, parallelism={}, skipped={}",
                    versionId, codeNodes.size(), uniqueNodes.size(), AiScanStepSupport.DOC_EXTRACT_PARALLELISM, skipped);

            AtomicInteger factCount = new AtomicInteger(0);
            AtomicInteger processed = new AtomicInteger(0);
            Set<String> visitedPaths = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (GraphNode node : uniqueNodes) {
                // 跳过已完成文件
                if (donePaths.contains(node.getSourcePath())) {
                    continue;
                }
                String filePath = node.getSourcePath();
                futures.add(CompletableFuture.runAsync(() -> {
                    support.markExtracting(projectId, versionId, filePath, "CODE_EXTRACT");
                    String content = readCodeContent(node, visitedPaths);
                    if (content == null || content.isBlank()) {
                        support.markExtractFailed(projectId, versionId, filePath, "CODE_EXTRACT", "empty content");
                        return;
                    }
                    processed.incrementAndGet();
                    // 向量化：support 内部已用专用有界线程池 + 内存水位背压，直接委托
                    support.vectorizeContent(projectId, versionId, "CODE", node.getSourcePath(), content);
                    try {
                        String codeContent = support.truncate(content, CODE_CONTENT_LIMIT);
                        FactExtractionResult result = support.cachedExtract("code", codeContent,
                                () -> codeFactAgent.extractFacts(projectId, codeContent, node.getSourcePath()),
                                FactExtractionResult.class,
                                r -> r == null || r.getItems() == null || r.getItems().isEmpty());
                        int count = persistAndBuildCodeFacts(projectId, versionId, node, result);
                        factCount.addAndGet(count);
                        support.markExtractDone(projectId, versionId, filePath, "CODE_EXTRACT",
                                "facts=" + count);
                        int done = processed.incrementAndGet();
                        if (done % 5 == 0 || done == totalNodes) {
                            support.updateTaskProgress(task, totalNodes, done, filePath);
                        }
                    } catch (Exception e) {
                        log.warn("Code fact extract failed for node {}: {}", node.getNodeKey(), e.getMessage());
                        support.markExtractFailed(projectId, versionId, filePath, "CODE_EXTRACT", e.getMessage());
                    }
                }, support.getCodeExtractExecutor()));
            }

            // 等待所有代码分析完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int totalFacts = factCount.get();
            int totalProcessed = processed.get();
            log.info("AI_CODE_EXTRACT completed: versionId={}, factCount={}, processed={}",
                    versionId, totalFacts, totalProcessed);

            String summary = totalFacts > 0
                    ? "AI 从代码抽取业务事实 " + totalFacts + " 条，分析类节点 " + totalProcessed + " 个"
                    : "⚠ 分析类节点 " + totalProcessed + " 个，未抽取到业务事实";
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary)
                    .processedCount(totalFacts).build();
        } catch (Exception e) {
            log.error("AI_CODE_EXTRACT failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * 将代码事实抽取结果落 Fact 表，并桥接为业务图谱功能节点。
     */
    private int persistAndBuildCodeFacts(String projectId, String versionId, GraphNode node,
                                         FactExtractionResult result) {
        if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
            return 0;
        }
        List<Fact> facts = new ArrayList<>();
        DocUnderstandingAgent.BusinessFactExtraction bridge =
                new DocUnderstandingAgent.BusinessFactExtraction();
        for (FactExtractionResult.FactItem item : result.getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            double confidence = item.getConfidence() != null
                    ? item.getConfidence().doubleValue() : 0.6;
            String key = item.getKey() != null && !item.getKey().isBlank()
                    ? item.getKey() : "code-feature:" + item.getName();
            support.addFact(facts, support.buildFact(projectId, versionId, "CODE_FEATURE", key, item.getName(),
                    node.getSourcePath(), item, confidence, SourceType.CODE_AI.name()));
            bridge.getFeatures().add(item.getName());
        }
        int count = support.saveAiFactsBatch(facts);
        // 复用 P0-B 的功能清单落图路径
        if (!bridge.getFeatures().isEmpty() && businessGraphBuilder != null) {
            try {
                businessGraphBuilder.buildBusinessGraph(projectId, versionId, bridge,
                        node.getSourcePath(), SourceType.CODE_AI.name());
            } catch (Exception e) {
                log.warn("Business graph build from code facts failed for node {}: {}",
                        node.getNodeKey(), e.getMessage());
            }
        }
        support.upsertClaimDrafts(projectId, versionId,
                codeFactAgent.toClaimDrafts(projectId, versionId, result, node.getSourcePath()));
        return count;
    }

    /**
     * 读取代码节点对应的源文件内容。按 sourcePath 去重，避免同文件多节点重复抽取。
     */
    private String readCodeContent(GraphNode node, Set<String> visitedPaths) {
        String path = node.getSourcePath();
        if (path == null || path.isBlank() || !visitedPaths.add(path)) {
            return null;
        }
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to read code content {}: {}", path, e.getMessage());
            return null;
        }
    }
}
