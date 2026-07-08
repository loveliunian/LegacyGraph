package io.github.legacygraph.task.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI_REVIEW_PREPARE — 低置信节点生成人工审核任务。
 */
@Slf4j
@Component
public class ReviewPrepareStep implements AiScanStepExecutor {

    /** 审核准备节点上限 */
    private static final int MAX_REVIEW_NODES = 50;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ReviewRecordRepository reviewRecordRepository;

    public ReviewPrepareStep(AiScanStepSupport support,
                             Neo4jGraphDao neo4jGraphDao,
                             ReviewRecordRepository reviewRecordRepository) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.reviewRecordRepository = reviewRecordRepository;
    }

    @Override
    public String getStepName() {
        return "AI_REVIEW_PREPARE";
    }

    @Override
    public int getOrder() {
        return 6;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.WRITE_INTENT;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        double minConfidence = ctx.getConfig() != null ? ctx.getConfig().getMinConfidence() : 0.0;
        ScanTask task = support.createTask(projectId, versionId, "AI_REVIEW_PREPARE", "低置信节点审核准备");
        try {
            // 拉取该版本节点，筛选低置信节点生成审核任务
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, versionId,
                    null, null, null, null, MAX_REVIEW_NODES * 4);
            int created = 0;
            for (GraphNode node : nodes) {
                if (created >= MAX_REVIEW_NODES) {
                    break;
                }
                double conf = node.getConfidence() != null ? node.getConfidence().doubleValue() : 0.0;
                if (conf >= minConfidence) {
                    continue;
                }
                if (createReviewRecord(projectId, versionId, node, conf)) {
                    created++;
                }
            }
            String summary = "生成低置信审核任务 " + created + " 条（阈值 " + minConfidence + "）";
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary)
                    .processedCount(created).build();
        } catch (Exception e) {
            log.error("AI_REVIEW_PREPARE failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    private boolean createReviewRecord(String projectId, String versionId, GraphNode node, double confidence) {
        try {
            // 去重：同一目标已有待审核记录则跳过
            long exists = reviewRecordRepository.selectCount(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, node.getId())
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return false;
            }
            ReviewRecord record = new ReviewRecord();
            record.setId(IdUtil.fastUUID());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetType("NODE");
            record.setTargetId(node.getId());
            record.setTargetName(node.getNodeName());
            record.setGraphType(node.getNodeType());
            record.setConfidence(confidence);
            record.setPriority(confidence < 0.3 ? "HIGH" : "MEDIUM");
            record.setStatus("PENDING");
            record.setComment("AI 编排：低置信节点，建议人工审核");
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for node {}: {}", node.getId(), e.getMessage());
            return false;
        }
    }
}
