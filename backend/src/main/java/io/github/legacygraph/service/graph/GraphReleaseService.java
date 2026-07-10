package io.github.legacygraph.service.graph;

import io.github.legacygraph.common.GraphReleaseStatus;
import io.github.legacygraph.config.GraphReleaseConfig;
import io.github.legacygraph.entity.GraphRelease;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.GraphReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 图谱发布服务 — 管理图谱发布记录的状态机生命周期。
 * <p>
 * 状态机流转：{@code DRAFT → VALIDATING → PUBLISHED | FAILED}
 * </p>
 * <p>
 * 幂等保证：通过 {@code (project_id, scan_version_id)} 唯一索引，
 * {@link #startValidation} 对同 project+version 的重复调用返回已有记录，不创建第二条。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphReleaseService {

    private final GraphReleaseRepository repository;
    private final GraphReleaseConfig config;

    /** 状态机非法转移错误码 */
    private static final int ERR_INVALID_STATE_TRANSITION = 6001;
    /** 发布功能未开启错误码 */
    private static final int ERR_RELEASE_DISABLED = 6002;
    /** 发布记录不存在错误码 */
    private static final int ERR_RELEASE_NOT_FOUND = 6003;

    /**
     * 启动图谱校验（幂等）。
     * <p>
     * 若同 project+version 已存在发布记录，直接返回已有记录（幂等）；
     * 否则创建 DRAFT 状态的新记录并立即转为 VALIDATING。
     * </p>
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID
     * @return 发布记录（状态为 VALIDATING 或已存在的其他状态）
     */
    public GraphRelease startValidation(String projectId, String scanVersionId) {
        if (!config.isEnabled()) {
            throw new BusinessException(ERR_RELEASE_DISABLED,
                    "图谱发布功能未开启，请设置 legacygraph.graph-release.enabled=true");
        }

        // 幂等检查：同 project+version 已有记录则直接返回
        GraphRelease existing = repository.findByProjectAndVersion(projectId, scanVersionId);
        if (existing != null) {
            log.debug("GraphRelease already exists for project={}, scanVersion={}, status={}",
                    projectId, scanVersionId, existing.getStatus());
            return existing;
        }

        // 创建新记录：DRAFT → VALIDATING
        GraphRelease release = new GraphRelease();
        release.setProjectId(projectId);
        release.setScanVersionId(scanVersionId);
        release.setStatus(GraphReleaseStatus.VALIDATING.name());
        release.setCreatedAt(LocalDateTime.now());

        repository.insert(release);
        log.info("GraphRelease created and moved to VALIDATING: id={}, project={}, scanVersion={}",
                release.getId(), projectId, scanVersionId);
        return release;
    }

    /**
     * 标记发布成功。
     * <p>
     * 状态从 VALIDATING 变为 PUBLISHED，记录 publishedAt 时间。
     * </p>
     *
     * @param graphReleaseId 发布记录 ID
     * @return 更新后的发布记录
     * @throws BusinessException 记录不存在或状态非 VALIDATING 时抛出
     */
    public GraphRelease markPublished(String graphReleaseId) {
        GraphRelease release = requireRelease(graphReleaseId);

        if (!GraphReleaseStatus.VALIDATING.name().equals(release.getStatus())) {
            throw new BusinessException(ERR_INVALID_STATE_TRANSITION,
                    "无法从状态 " + release.getStatus() + " 转为 PUBLISHED，仅 VALIDATING 状态可发布");
        }

        release.setStatus(GraphReleaseStatus.PUBLISHED.name());
        release.setPublishedAt(LocalDateTime.now());
        repository.updateById(release);

        log.info("GraphRelease published: id={}, project={}, scanVersion={}",
                release.getId(), release.getProjectId(), release.getScanVersionId());
        return release;
    }

    /**
     * 标记发布失败。
     * <p>
     * 状态变为 FAILED，记录 failureReasons。
     * </p>
     *
     * @param graphReleaseId 发布记录 ID
     * @param reasons        失败原因列表
     * @return 更新后的发布记录
     * @throws BusinessException 记录不存在时抛出
     */
    public GraphRelease markFailed(String graphReleaseId, List<String> reasons) {
        GraphRelease release = requireRelease(graphReleaseId);

        release.setStatus(GraphReleaseStatus.FAILED.name());
        release.setFailureReasons(reasons);
        repository.updateById(release);

        log.info("GraphRelease marked FAILED: id={}, reasons={}", release.getId(), reasons);
        return release;
    }

    /**
     * 根据 ID 查询发布记录，不存在时抛出业务异常。
     */
    private GraphRelease requireRelease(String graphReleaseId) {
        GraphRelease release = repository.selectById(graphReleaseId);
        if (release == null) {
            throw new BusinessException(ERR_RELEASE_NOT_FOUND, "图谱发布记录不存在: " + graphReleaseId);
        }
        return release;
    }
}
