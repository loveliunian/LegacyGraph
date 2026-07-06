package io.github.legacygraph.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目概览响应
 */
@Data
public class ProjectOverviewResponse {

    private SourceStatus sourceStatus;
    private GraphStats graphStats;
    private List<ScanVersionItem> recentScanVersions;
    private List<ReviewItem> recentReviews;

    @Data
    public static class SourceStatus {
        private RepoStatus repos;
        private DbStatus databases;
        private DocStatus documents;
    }

    @Data
    public static class RepoStatus {
        private int configured;
        private int scanned;
        private int failed;
    }

    @Data
    public static class DbStatus {
        private int configured;
        private int scanned;
        private int failed;
    }

    @Data
    public static class DocStatus {
        private int uploaded;
        private int parsed;
        private int failed;
    }

    @Data
    public static class GraphStats {
        private long totalNodes;
        private long totalEdges;
        private long confirmedNodes;
        private long confirmedEdges;
        private long pendingNodes;
        private long pendingEdges;
        private double avgConfidence;
        private double nodeConfirmationRate;
        private double edgeConfirmationRate;
        private long approvedCount;
        private long pendingCount;
        private long withEvidenceCount;
    }

    @Data
    public static class ScanVersionItem {
        private String id;
        private String versionNo;
        private String branchName;
        private String scanStatus;
        private String createdAt;
    }

    @Data
    public static class ReviewItem {
        private String id;
        private String targetName;
        private String targetType;
        private String status;
        private String reviewedAt;
        private String createdAt;
    }
}
