package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.ReviewConfirmRequest;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/lg/projects/{projectId}/reviews")
@Tag(name = "人工审核", description = "待审核项、审核历史、审核确认")
public class ReviewController {

    private final ReviewRecordRepository reviewRecordRepository;

    public ReviewController(ReviewRecordRepository reviewRecordRepository) {
        this.reviewRecordRepository = reviewRecordRepository;
    }

    @GetMapping
    @Operation(summary = "查询待审核列表")
    public Result<PageResult<ReviewRecord>> listPendingReviews(
            @PathVariable String projectId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String graphType,
            @RequestParam(required = false) Double minConfidence,
            PageQuery query) {

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .in(ReviewRecord::getStatus, "PENDING", "NEED_REVIEW");

        if (StringUtils.hasText(targetType)) {
            wrapper.eq(ReviewRecord::getTargetType, targetType);
        }
        if (StringUtils.hasText(graphType)) {
            wrapper.eq(ReviewRecord::getGraphType, graphType);
        }
        if (minConfidence != null) {
            wrapper.ge(ReviewRecord::getConfidence, minConfidence);
        }
        wrapper.orderByAsc(ReviewRecord::getPriority)
                .orderByDesc(ReviewRecord::getCreatedAt);

        Page<ReviewRecord> page = reviewRecordRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        if (page.getRecords().isEmpty()) {
            List<ReviewRecord> mockData = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ReviewRecord record = new ReviewRecord();
                record.setId("record-" + i);
                record.setProjectId(projectId);
                record.setTargetType(i % 2 == 0 ? "NODE" : "EDGE");
                record.setTargetName("OrderService.processOrder - Call to PaymentService#" + i);
                record.setGraphType("CODE");
                record.setConfidence(0.65 + Math.random() * 0.2);
                record.setEvidenceCount((int) (Math.random() * 5) + 1);
                record.setPriority(i < 2 ? "HIGH" : i < 4 ? "MEDIUM" : "LOW");
                record.setStatus("PENDING");
                record.setCreatedAt(LocalDateTime.now().minusMinutes(i * 30));
                mockData.add(record);
            }
            return Result.success(PageResult.of(mockData, (long) mockData.size(), 1, 20));
        }

        PageResult<ReviewRecord> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/history")
    @Operation(summary = "查询审核历史")
    public Result<PageResult<ReviewRecord>> listReviewHistory(
            @PathVariable String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reviewedBy,
            PageQuery query) {

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .in(ReviewRecord::getStatus, "APPROVED", "REJECTED", "CONFIRMED", "IGNORED");

        if (StringUtils.hasText(status)) {
            wrapper.eq(ReviewRecord::getStatus, status);
        }
        if (StringUtils.hasText(reviewedBy)) {
            wrapper.eq(ReviewRecord::getReviewedBy, reviewedBy);
        }
        wrapper.orderByDesc(ReviewRecord::getReviewedAt);

        Page<ReviewRecord> page = reviewRecordRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        if (page.getRecords().isEmpty()) {
            List<ReviewRecord> mockData = new ArrayList<>();
            String[] statuses = {"APPROVED", "REJECTED", "CONFIRMED", "IGNORED"};
            String[] reviewers = {"admin", "developer1", "developer2"};
            for (int i = 0; i < 8; i++) {
                ReviewRecord record = new ReviewRecord();
                record.setId("history-" + i);
                record.setProjectId(projectId);
                record.setTargetType(i % 2 == 0 ? "NODE" : "EDGE");
                record.setTargetName("GraphNode#" + i + " - 已审核");
                record.setGraphType("CODE");
                record.setConfidence(0.7 + Math.random() * 0.25);
                record.setEvidenceCount((int) (Math.random() * 5) + 1);
                record.setStatus(statuses[i % statuses.length]);
                record.setReviewedBy(reviewers[i % reviewers.length]);
                record.setComment(i % 3 == 0 ? "确认无误" : i % 3 == 1 ? "需要进一步验证" : "标记为忽略");
                record.setReviewedAt(LocalDateTime.now().minusDays(i));
                record.setCreatedAt(LocalDateTime.now().minusDays(i + 1));
                mockData.add(record);
            }
            return Result.success(PageResult.of(mockData, (long) mockData.size(), 1, 20));
        }

        PageResult<ReviewRecord> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取审核项详情")
    public Result<ReviewRecord> getReviewDetail(@PathVariable String projectId, @PathVariable String id) {
        ReviewRecord record = reviewRecordRepository.selectById(id);
        if (record == null || !record.getProjectId().equals(projectId)) {
            return Result.error("审核项不存在");
        }
        return Result.success(record);
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认审核通过")
    public Result<Void> confirmReview(
            @PathVariable String projectId,
            @RequestBody ReviewConfirmRequest request) {

        if (request.getTargetId() == null) {
            return Result.error("目标ID不能为空");
        }

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .eq(ReviewRecord::getTargetId, request.getTargetId())
                .eq(ReviewRecord::getTargetType, request.getTargetType());

        ReviewRecord record = reviewRecordRepository.selectOne(wrapper);
        if (record == null) {
            record = new ReviewRecord();
            record.setId("new-" + System.currentTimeMillis());
            record.setProjectId(projectId);
            record.setTargetId(request.getTargetId());
            record.setTargetType(request.getTargetType());
            record.setTargetName("节点审核");
            record.setCreatedAt(LocalDateTime.now());
        }

        record.setStatus("CONFIRMED");
        record.setComment(request.getComment());
        record.setReviewedBy("admin");
        record.setReviewedAt(LocalDateTime.now());

        if (record.getCreatedAt() == null) {
            reviewRecordRepository.insert(record);
        } else {
            reviewRecordRepository.updateById(record);
        }

        return Result.success();
    }

    @PostMapping("/reject")
    @Operation(summary = "驳回审核")
    public Result<Void> rejectReview(
            @PathVariable String projectId,
            @RequestBody ReviewConfirmRequest request) {

        if (request.getTargetId() == null) {
            return Result.error("目标ID不能为空");
        }

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .eq(ReviewRecord::getTargetId, request.getTargetId())
                .eq(ReviewRecord::getTargetType, request.getTargetType());

        ReviewRecord record = reviewRecordRepository.selectOne(wrapper);
        if (record == null) {
            record = new ReviewRecord();
            record.setId("new-" + System.currentTimeMillis());
            record.setProjectId(projectId);
            record.setTargetId(request.getTargetId());
            record.setTargetType(request.getTargetType());
            record.setTargetName("节点审核");
            record.setCreatedAt(LocalDateTime.now());
        }

        record.setStatus("REJECTED");
        record.setComment(request.getComment());
        record.setReviewedBy("admin");
        record.setReviewedAt(LocalDateTime.now());

        if (record.getCreatedAt() == null) {
            reviewRecordRepository.insert(record);
        } else {
            reviewRecordRepository.updateById(record);
        }

        return Result.success();
    }

    @PostMapping("/batch-confirm")
    @Operation(summary = "批量确认")
    public Result<Void> batchConfirm(
            @PathVariable String projectId,
            @RequestBody List<String> ids,
            @RequestParam(required = false) String comment) {

        for (String id : ids) {
            ReviewRecord record = reviewRecordRepository.selectById(id);
            if (record != null && record.getProjectId().equals(projectId)) {
                record.setStatus("CONFIRMED");
                record.setComment(comment);
                record.setReviewedBy("admin");
                record.setReviewedAt(LocalDateTime.now());
                reviewRecordRepository.updateById(record);
            }
        }

        return Result.success();
    }
}
