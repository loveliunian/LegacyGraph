package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.ReviewConfirmRequest;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/lg/reviews")
@Tag(name = "人工确认", description = "查询待确认节点、人工确认节点")
public class ReviewController {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final ReviewRecordRepository reviewRecordRepository;

    public ReviewController(GraphNodeRepository graphNodeRepository,
                           GraphEdgeRepository graphEdgeRepository,
                           ReviewRecordRepository reviewRecordRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.reviewRecordRepository = reviewRecordRepository;
    }

    @GetMapping("/pending")
    @Operation(summary = "查询待确认节点和关系")
    public Result<PageResult<Object>> getPending(
            String versionId,
            BigDecimal minConfidence,
            Integer pageNum,
            Integer pageSize) {
        // 查询低置信度节点
        LambdaQueryWrapper<GraphNode> nodeWrapper = new LambdaQueryWrapper<>();
        nodeWrapper.eq(GraphNode::getVersionId, versionId)
                .lt(GraphNode::getConfidence, minConfidence)
                .eq(GraphNode::getStatus, "PENDING_CONFIRM");
        Page<GraphNode> nodePage = graphNodeRepository.page(new Page<>(pageNum, pageSize), nodeWrapper);

        // 这里简化处理，只返回节点
        PageResult<Object> result = PageResult.of(
                (List) nodePage.getRecords(),
                nodePage.getTotal(),
                pageNum,
                pageSize
        );
        return Result.success(result);
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认或驳回节点/关系")
    public Result<Void> confirm(@RequestBody ReviewConfirmRequest request) {
        try {
            String targetType = request.getTargetType();
            String targetId = request.getTargetId();
            String reviewStatus = request.getReviewStatus();

            // 更新状态
            if ("NODE".equals(targetType)) {
                GraphNode node = graphNodeRepository.getById(targetId);
                if (node != null) {
                    if ("CONFIRMED".equals(reviewStatus)) {
                        node.setStatus("CONFIRMED");
                        node.setConfidence(BigDecimal.ONE);
                    } else if ("REJECTED".equals(reviewStatus)) {
                        node.setStatus("REJECTED");
                    }
                    graphNodeRepository.updateById(node);
                }
            } else if ("EDGE".equals(targetType)) {
                GraphEdge edge = graphEdgeRepository.getById(targetId);
                if (edge != null) {
                    if ("CONFIRMED".equals(reviewStatus)) {
                        edge.setStatus("CONFIRMED");
                        edge.setConfidence(BigDecimal.ONE);
                    } else if ("REJECTED".equals(reviewStatus)) {
                        edge.setStatus("REJECTED");
                    }
                    graphEdgeRepository.updateById(edge);
                }
            }

            // 记录审核
            ReviewRecord record = new ReviewRecord();
            record.setProjectId(null); // TODO: 获取项目ID
            record.setVersionId(null); // TODO: 获取版本ID
            record.setTargetType(targetType);
            record.setTargetId(targetId);
            record.setReviewStatus(reviewStatus);
            record.setReviewer("currentUser"); // TODO: 获取当前用户
            record.setReviewComment(request.getComment());
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.save(record);

            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
