package io.github.legacygraph.graphify;

import io.github.legacygraph.dao.Neo4jGraphDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 构建 Graphify 导入快照，供版本差异、漂移检测等读模型复用。
 */
@Service
@RequiredArgsConstructor
public class GraphifyImportSnapshotService {

    private static final List<String> GRAPHIFY_SOURCE_TYPES = List.of("GRAPHIFY_AST", "GRAPHIFY_SEMANTIC");

    private final GraphifyImportJobRepository jobRepository;
    private final Neo4jGraphDao graphDao;

    /**
     * 根据项目和扫描版本构建最近一次成功导入的 Graphify 快照。
     */
    public GraphifyImportSnapshot buildSnapshot(String projectId, String versionId) {
        Optional<GraphifyImportJob> latestJob = jobRepository
            .findByProjectId(projectId)
            .stream()
            .filter(j -> versionId.equals(j.getVersionId()))
            .filter(j -> j.getStatus() == GraphifyImportJob.Status.IMPORTED)
            .max(Comparator.comparing(
                j -> j.getFinishedAt() != null ? j.getFinishedAt() : LocalDateTime.MIN));

        if (latestJob.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("未找到版本 %s 的成功导入作业", versionId));
        }

        GraphifyImportJob job = latestJob.get();
        Set<String> nodeKeys = Optional.ofNullable(
            graphDao.queryNodeKeysBySourceTypes(projectId, versionId, GRAPHIFY_SOURCE_TYPES)
        ).orElse(Set.of());
        Set<String> edgeKeys = Optional.ofNullable(
            graphDao.queryEdgeKeysBySourceTypes(projectId, versionId, GRAPHIFY_SOURCE_TYPES)
        ).orElse(Set.of());

        return new GraphifyImportSnapshot(
            projectId,
            versionId,
            job.getGraphifyVersion(),
            job.getSourceCommit(),
            job.getFinishedAt(),
            nodeKeys,
            edgeKeys
        );
    }
}
