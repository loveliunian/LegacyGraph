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

import io.github.legacygraph.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/lg/projects/{projectId}/reviews")
@Tag(name = "人工审核", description = "待审核项、审核历史、审核确认")
public class ReviewController {

    private final ReviewRecordRepository reviewRecordRepository;
    private final JwtUtil jwtUtil;

    public ReviewController(ReviewRecordRepository reviewRecordRepository, JwtUtil jwtUtil) {
        this.reviewRecordRepository = reviewRecordRepository;
        this.jwtUtil = jwtUtil;
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
            @RequestBody ReviewConfirmRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (request.getTargetId() == null) {
            return Result.error("目标ID不能为空");
        }

        // 从 token 获取当前审核人
        String reviewedBy = "admin";
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (jwtUtil.validateToken(token)) {
                reviewedBy = jwtUtil.getUsernameFromToken(token);
            }
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
        record.setReviewedBy(reviewedBy);
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
            @RequestBody ReviewConfirmRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (request.getTargetId() == null) {
            return Result.error("目标ID不能为空");
        }

        // 从 token 获取当前审核人
        String reviewedBy = "admin";
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (jwtUtil.validateToken(token)) {
                reviewedBy = jwtUtil.getUsernameFromToken(token);
            }
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
        record.setReviewedBy(reviewedBy);
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
            @RequestParam(required = false) String comment,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        // 从 token 获取当前审核人
        String reviewedBy = "admin";
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (jwtUtil.validateToken(token)) {
                reviewedBy = jwtUtil.getUsernameFromToken(token);
            }
        }

        for (String id : ids) {
            ReviewRecord record = reviewRecordRepository.selectById(id);
            if (record != null && record.getProjectId().equals(projectId)) {
                record.setStatus("CONFIRMED");
                if (comment != null) {
                    record.setComment(comment);
                }
                record.setReviewedBy(reviewedBy);
                record.setReviewedAt(LocalDateTime.now());
                reviewRecordRepository.updateById(record);
            }
        }

        return Result.success();
    }
}
