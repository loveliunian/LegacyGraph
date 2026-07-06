package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.ProjectOverviewResponse;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.repository.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目概览服务
 */
@Service
public class ProjectOverviewService {

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final ReviewRecordRepository reviewRecordRepository;

    public ProjectOverviewService(CodeRepoRepository codeRepoRepository,
                                  DbConnectionRepository dbConnectionRepository,
                                  DocumentRepository documentRepository,
                                  Neo4jGraphDao neo4jGraphDao,
                                  ScanVersionRepository scanVersionRepository,
                                  ReviewRecordRepository reviewRecordRepository) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.reviewRecordRepository = reviewRecordRepository;
    }

    /**
     * 项目概览（缓存：仪表盘首屏，5+ 查询 + Neo4j 聚合；短 TTL 容忍轻微陈旧）
     */
    @Cacheable(cacheNames = "project-overview", key = "#projectId")
    public ProjectOverviewResponse getOverview(String projectId) {
        ProjectOverviewResponse response = new ProjectOverviewResponse();

        // 1. 资料接入状态
        response.setSourceStatus(buildSourceStatus(projectId));

        // 2. 图谱统计
        response.setGraphStats(buildGraphStats(projectId));

        // 3. 最近扫描版本
        List<ScanVersion> versions = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .last("LIMIT 5")
                .list();
        response.setRecentScanVersions(versions.stream().map(v -> {
            ProjectOverviewResponse.ScanVersionItem item = new ProjectOverviewResponse.ScanVersionItem();
            item.setId(v.getId());
            item.setVersionNo(v.getVersionNo());
            item.setBranchName(v.getBranchName());
            item.setScanStatus(v.getScanStatus());
            item.setCreatedAt(v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
            return item;
        }).collect(Collectors.toList()));

        // 4. 最近审核记录
        List<ReviewRecord> reviews = reviewRecordRepository.lambdaQuery()
                .eq(ReviewRecord::getProjectId, projectId)
                .orderByDesc(ReviewRecord::getCreatedAt)
                .last("LIMIT 5")
                .list();
        response.setRecentReviews(reviews.stream().map(r -> {
            ProjectOverviewResponse.ReviewItem item = new ProjectOverviewResponse.ReviewItem();
            item.setId(r.getId());
            item.setTargetName(r.getTargetName());
            item.setTargetType(r.getTargetType());
            item.setStatus(r.getStatus());
            item.setReviewedAt(r.getReviewedAt() != null ? r.getReviewedAt().toString() : null);
            item.setCreatedAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            return item;
        }).collect(Collectors.toList()));

        return response;
    }

    private ProjectOverviewResponse.SourceStatus buildSourceStatus(String projectId) {
        ProjectOverviewResponse.SourceStatus status = new ProjectOverviewResponse.SourceStatus();

        LambdaQueryWrapper<CodeRepo> repoWrapper = new LambdaQueryWrapper<>();
        repoWrapper.eq(CodeRepo::getProjectId, projectId);
        List<CodeRepo> repos = codeRepoRepository.selectList(repoWrapper);
        ProjectOverviewResponse.RepoStatus repoStatus = new ProjectOverviewResponse.RepoStatus();
        repoStatus.setConfigured(repos.size());
        repoStatus.setScanned((int) repos.stream().filter(r -> "READY".equals(r.getStatus())).count());
        repoStatus.setFailed((int) repos.stream().filter(r -> r.getStatus() != null && r.getStatus().contains("FAILED")).count());
        status.setRepos(repoStatus);

        LambdaQueryWrapper<DbConnection> dbWrapper = new LambdaQueryWrapper<>();
        dbWrapper.eq(DbConnection::getProjectId, projectId);
        List<DbConnection> dbs = dbConnectionRepository.selectList(dbWrapper);
        ProjectOverviewResponse.DbStatus dbStatus = new ProjectOverviewResponse.DbStatus();
        dbStatus.setConfigured(dbs.size());
        dbStatus.setScanned((int) dbs.stream().filter(d -> "READY".equals(d.getStatus())).count());
        dbStatus.setFailed((int) dbs.stream().filter(d -> d.getStatus() != null && d.getStatus().contains("FAILED")).count());
        status.setDatabases(dbStatus);

        LambdaQueryWrapper<Document> docWrapper = new LambdaQueryWrapper<>();
        docWrapper.eq(Document::getProjectId, projectId);
        List<Document> docs = documentRepository.selectList(docWrapper);
        ProjectOverviewResponse.DocStatus docStatus = new ProjectOverviewResponse.DocStatus();
        docStatus.setUploaded(docs.size());
        docStatus.setParsed((int) docs.stream().filter(d -> "PARSED".equals(d.getParseStatus())).count());
        docStatus.setFailed((int) docs.stream().filter(d -> d.getParseStatus() != null && d.getParseStatus().contains("FAILED")).count());
        status.setDocuments(docStatus);

        return status;
    }

    private ProjectOverviewResponse.GraphStats buildGraphStats(String projectId) {
        ProjectOverviewResponse.GraphStats stats = new ProjectOverviewResponse.GraphStats();

        Map<String, Object> raw = neo4jGraphDao.graphStats(projectId);
        long totalNodes = toLong(raw.get("totalNodes"));
        long totalEdges = toLong(raw.get("totalEdges"));
        long confirmedNodes = toLong(raw.get("confirmedNodes"));
        long confirmedEdges = toLong(raw.get("confirmedEdges"));
        long pendingNodes = toLong(raw.get("pendingNodes"));
        long pendingEdges = toLong(raw.get("pendingEdges"));
        double avgConfidence = toDouble(raw.get("avgConfidence"));
        long withEvidenceCount = toLong(raw.get("withEvidenceCount"));

        double nodeConfirmationRate = totalNodes > 0 ? (double) confirmedNodes / totalNodes * 100 : 0;
        double edgeConfirmationRate = totalEdges > 0 ? (double) confirmedEdges / totalEdges * 100 : 0;

        stats.setTotalNodes(totalNodes);
        stats.setTotalEdges(totalEdges);
        stats.setConfirmedNodes(confirmedNodes);
        stats.setConfirmedEdges(confirmedEdges);
        stats.setPendingNodes(pendingNodes);
        stats.setPendingEdges(pendingEdges);
        stats.setAvgConfidence(avgConfidence);
        stats.setNodeConfirmationRate(Math.round(nodeConfirmationRate * 10) / 10.0);
        stats.setEdgeConfirmationRate(Math.round(edgeConfirmationRate * 10) / 10.0);
        stats.setApprovedCount(confirmedNodes);
        stats.setPendingCount(pendingNodes);
        stats.setWithEvidenceCount(withEvidenceCount);

        return stats;
    }

    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
