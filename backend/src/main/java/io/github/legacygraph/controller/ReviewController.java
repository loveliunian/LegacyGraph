package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.ReviewConfirmRequest;
import io.github.legacygraph.dto.ReviewCreateRequest;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import io.github.legacygraph.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/lg/projects/{projectId}/reviews")
@Tag(name = "人工审核", description = "待审核项、审核历史、审核确认")
public class ReviewController {

    private final ReviewRecordRepository reviewRecordRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final JwtUtil jwtUtil;

    public ReviewController(ReviewRecordRepository reviewRecordRepository,
                            ScanVersionRepository scanVersionRepository,
                            JwtUtil jwtUtil) {
        this.reviewRecordRepository = reviewRecordRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 解析审核操作的 versionId：
     * 优先用请求中携带的；未提供时查项目最新扫描版本。
     */
    private String resolveVersionId(String projectId, String requestVersionId) {
        if (StringUtils.hasText(requestVersionId)) {
            return requestVersionId;
        }
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .last("LIMIT 1");
        ScanVersion latest = scanVersionRepository.selectOne(wrapper);
        if (latest != null) {
            return latest.getId();
        }
        // 兜底：用零 UUID 避免违反 NOT NULL 约束
        return "00000000-0000-0000-0000-000000000000";
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

    @PostMapping
    @Operation(summary = "创建待审核项")
    public Result<ReviewRecord> createReview(
            @PathVariable String projectId,
            @RequestBody ReviewCreateRequest request) {

        if (request == null || !StringUtils.hasText(request.getTargetId())) {
            return Result.error("目标ID不能为空");
        }

        String targetType = StringUtils.hasText(request.getTargetType()) ? request.getTargetType() : "NODE";
        LambdaQueryWrapper<ReviewRecord> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(ReviewRecord::getProjectId, projectId)
                .eq(ReviewRecord::getTargetType, targetType)
                .eq(ReviewRecord::getTargetId, request.getTargetId())
                .in(ReviewRecord::getStatus, "PENDING", "NEED_REVIEW")
                .last("LIMIT 1");

        ReviewRecord existing = reviewRecordRepository.selectOne(existingWrapper);
        if (existing != null) {
            return Result.success(existing);
        }

        ReviewRecord record = new ReviewRecord();
        record.setId(UUID.randomUUID().toString());
        record.setProjectId(projectId);
        record.setVersionId(resolveVersionId(projectId, request.getVersionId()));
        record.setTargetType(targetType);
        record.setTargetId(request.getTargetId());
        record.setTargetName(StringUtils.hasText(request.getTargetName()) ? request.getTargetName() : request.getTargetId());
        record.setGraphType(StringUtils.hasText(request.getGraphType()) ? request.getGraphType() : "DRIFT");
        record.setConfidence(request.getConfidence() != null ? request.getConfidence() : 0.5D);
        record.setEvidenceCount(request.getEvidenceCount());
        record.setPriority(StringUtils.hasText(request.getPriority()) ? request.getPriority() : "MEDIUM");
        record.setStatus("PENDING");
        record.setComment(request.getComment());
        record.setCreatedAt(LocalDateTime.now());

        reviewRecordRepository.insert(record);
        return Result.success(record);
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

        String versionId = resolveVersionId(projectId, request.getVersionId());

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .eq(ReviewRecord::getTargetId, request.getTargetId())
                .eq(ReviewRecord::getTargetType, request.getTargetType());

        ReviewRecord record = reviewRecordRepository.selectOne(wrapper);
        boolean isNew = false;
        if (record == null) {
            record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetId(request.getTargetId());
            record.setTargetType(request.getTargetType());
            record.setTargetName("节点审核");
            record.setCreatedAt(LocalDateTime.now());
            isNew = true;
        }

        record.setStatus("CONFIRMED");
        record.setComment(request.getComment());
        record.setReviewedBy(reviewedBy);
        record.setReviewedAt(LocalDateTime.now());

        if (isNew) {
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

        String versionId = resolveVersionId(projectId, request.getVersionId());

        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewRecord::getProjectId, projectId)
                .eq(ReviewRecord::getTargetId, request.getTargetId())
                .eq(ReviewRecord::getTargetType, request.getTargetType());

        ReviewRecord record = reviewRecordRepository.selectOne(wrapper);
        boolean isNew = false;
        if (record == null) {
            record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetId(request.getTargetId());
            record.setTargetType(request.getTargetType());
            record.setTargetName("节点审核");
            record.setCreatedAt(LocalDateTime.now());
            isNew = true;
        }

        record.setStatus("REJECTED");
        record.setComment(request.getComment());
        record.setReviewedBy(reviewedBy);
        record.setReviewedAt(LocalDateTime.now());

        if (isNew) {
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
