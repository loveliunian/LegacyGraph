package io.github.legacygraph.task.step;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI_PROCESS_TO_DOMAIN — LLM 批量归类 BusinessProcess → BusinessDomain（H27）。
 *
 * <p>用 LLM 做语义归类，产出 IN_DOMAIN 边（H01 已支持）。
 * 替代了原 BusinessGraphBuilder 中基于术语相似度的规则映射方法。</p>
 *
 * <p>开关：{@code AiScanConfig.llmProcessDomainClassification}（默认 ON）。</p>
 *
 * <p>降级策略：LLM 调用失败时不阻塞扫描，仅记录警告并跳过。</p>
 */
@Slf4j
@Component
public class BusinessProcessToDomainStep implements AiScanStepExecutor {

    /** LLM 批量归类每批最大 Process 数 */
    private static final int LLM_BATCH_SIZE = 50;
    /** LLM 返回的 IN_DOMAIN 边置信度 */
    private static final double LLM_DOMAIN_CONFIDENCE = 0.85;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final LlmGateway llmGateway;

    public BusinessProcessToDomainStep(AiScanStepSupport support,
                                        Neo4jGraphDao neo4jGraphDao,
                                        LlmGateway llmGateway) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.llmGateway = llmGateway;
    }

    @Override
    public String getStepName() {
        return "AI_PROCESS_TO_DOMAIN";
    }

    @Override
    public int getOrder() {
        return 7; // 在 FeatureMappingStep(6) 之后、UnderstandingEnhancementStep(8) 之前
    }

    @Override
    public ScanStep getScanStep() {
        // 复用 GAP_FINDING 阶段（与 KnowledgeGapStep 同属增强阶段）
        return ScanStep.GAP_FINDING;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();

        AiScanConfig config = ctx.getConfig();
        // H27: 独立开关 llmProcessDomainClassification，默认 ON
        boolean enabled = config == null || config.isLlmProcessDomainClassification();
        if (!enabled) {
            log.info("LLM Process→Domain classification disabled by config, skipping");
            return StepExecutionResult.builder().success(true)
                    .message("LLM Process→Domain classification disabled").build();
        }

        ScanTask task = support.createTask(projectId, versionId, "AI_PROCESS_TO_DOMAIN",
                "LLM 批量归类 BusinessProcess → BusinessDomain");

        try {
            // 1. 查询 BusinessProcess 和 BusinessDomain 节点
            List<GraphNode> processes = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.BusinessProcess.name(), null, null, null, 0);
            List<GraphNode> domains = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.BusinessDomain.name(), null, null, null, 0);

            if (processes.isEmpty() || domains.isEmpty()) {
                String msg = String.format("无 BusinessProcess(%d) 或 BusinessDomain(%d)，跳过 LLM 归类",
                        processes.size(), domains.size());
                support.completeTask(task, msg, null);
                return StepExecutionResult.builder().success(true).message(msg).build();
            }

            // 2. 分批调用 LLM 归类
            int totalEdges = 0;
            int failedBatches = 0;
            for (int i = 0; i < processes.size(); i += LLM_BATCH_SIZE) {
                int end = Math.min(i + LLM_BATCH_SIZE, processes.size());
                List<GraphNode> batch = processes.subList(i, end);
                try {
                    int edges = classifyBatchWithLlm(projectId, versionId, batch, domains);
                    totalEdges += edges;
                } catch (Exception e) {
                    failedBatches++;
                    log.warn("LLM 归类批次 {}-{} 失败: {}", i, end, e.getMessage());
                }
            }

            String summary = String.format("LLM 归类完成: %d 个 Process → Domain, %d 条 IN_DOMAIN 边%s",
                    processes.size(), totalEdges,
                    failedBatches > 0 ? "（" + failedBatches + " 批失败）" : "");
            if (failedBatches > 0) {
                summary = "⚠ " + summary;
            }
            support.completeTask(task, summary, null);
            log.info("ProcessToDomain completed: projectId={}, versionId={}, {}",
                    projectId, versionId, summary);
            return StepExecutionResult.builder()
                    .success(true)
                    .warning(failedBatches > 0)
                    .message(summary)
                    .processedCount(totalEdges)
                    .build();
        } catch (Exception e) {
            log.error("ProcessToDomain failed: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage(), e);
            support.completeTask(task, null, "LLM 归类失败（不影响基础扫描）: " + e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * 用 LLM 对一批 BusinessProcess 做语义归类到 BusinessDomain。
     *
     * @return 落地的 IN_DOMAIN 边数
     */
    private int classifyBatchWithLlm(String projectId, String versionId,
                                      List<GraphNode> processes, List<GraphNode> domains) throws Exception {
        // 构建 prompt
        StringBuilder domainList = new StringBuilder();
        for (int i = 0; i < domains.size(); i++) {
            GraphNode d = domains.get(i);
            domainList.append(i).append(": ")
                    .append(d.getNodeName() != null ? d.getNodeName() : d.getNodeKey())
                    .append("\n");
        }

        StringBuilder processList = new StringBuilder();
        for (int i = 0; i < processes.size(); i++) {
            GraphNode p = processes.get(i);
            processList.append(i).append(": ")
                    .append(p.getNodeName() != null ? p.getNodeName() : p.getNodeKey())
                    .append(" — ").append(p.getDescription() != null ? p.getDescription() : "")
                    .append("\n");
        }

        String systemPrompt = "你是业务架构分析专家。给定业务流程列表和业务域列表，"
                + "为每个业务流程选择最匹配的业务域。输出 JSON 数组，每个元素形如 "
                + "{\"processIndex\": 0, \"domainIndex\": 0}。只返回 JSON，不要解释。";

        String userPrompt = "业务域列表：\n" + domainList + "\n"
                + "业务流程列表：\n" + processList + "\n"
                + "请为每个流程选择最匹配的业务域索引。";

        // 调用 LLM
        JsonNode response = llmGateway.call(projectId, systemPrompt, userPrompt, JsonNode.class);
        if (response == null || !response.isArray()) {
            log.warn("LLM 返回非 JSON 数组，跳过本批");
            return 0;
        }

        // 解析并构建 GraphEdge 列表（与 BusinessGraphBuilder.mapBusinessProcessesToDomains 同模式）
        List<GraphEdge> edges = new ArrayList<>();
        for (JsonNode item : response) {
            int pIdx = item.path("processIndex").asInt(-1);
            int dIdx = item.path("domainIndex").asInt(-1);
            if (pIdx < 0 || pIdx >= processes.size() || dIdx < 0 || dIdx >= domains.size()) {
                continue;
            }
            GraphNode process = processes.get(pIdx);
            GraphNode domain = domains.get(dIdx);
            if (process.getId() == null || domain.getId() == null
                    || process.getNodeKey() == null || domain.getNodeKey() == null) {
                continue;
            }
            edges.add(buildInDomainEdge(projectId, versionId, process, domain));
        }

        // 批量写入图谱（与 BusinessGraphBuilder 同模式：mergeEdgesBatch）
        if (edges.isEmpty()) {
            return 0;
        }
        int merged = neo4jGraphDao.mergeEdgesBatch(edges);
        log.info("LLM process→domain batch merged {} edges (batch size={})",
                merged, processes.size());
        return merged;
    }

    /**
     * 构建 IN_DOMAIN 边 POJO（与 BusinessGraphBuilder.buildEdgePOJO 同结构）。
     */
    private GraphEdge buildInDomainEdge(String projectId, String versionId,
                                         GraphNode process, GraphNode domain) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(process.getId());
        edge.setToNodeId(domain.getId());
        edge.setEdgeType(EdgeType.IN_DOMAIN.name());
        edge.setEdgeKey(process.getNodeKey() + "->in_domain->" + domain.getNodeKey());
        edge.setSourceType("AI_LLM");
        edge.setConfidence(BigDecimal.valueOf(LLM_DOMAIN_CONFIDENCE));
        edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }
}
