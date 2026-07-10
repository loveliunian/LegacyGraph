package io.github.legacygraph.service.graph;

import io.github.legacygraph.common.GraphReleaseStatus;
import io.github.legacygraph.config.GraphReleaseConfig;
import io.github.legacygraph.entity.GraphRelease;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.GraphReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GraphReleaseService} 单元测试 — 验证状态机逻辑与幂等性。
 * <p>
 * 注意：{@link GraphReleaseRepository#findByProjectAndVersion} 是接口 default 方法，
 * Mockito mock 时不执行其实现体，需直接 mock 该方法而非底层 {@code selectOne}。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GraphReleaseServiceTest {

    @Mock
    private GraphReleaseRepository repository;

    private GraphReleaseConfig config;

    private GraphReleaseService service;

    @BeforeEach
    void setUp() {
        config = new GraphReleaseConfig();
        config.setEnabled(true);
        service = new GraphReleaseService(repository, config);
    }

    // ========== startValidation ==========

    @Test
    void startValidation_createsNewRecordInValidatingStatus_whenNoneExists() {
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(null);
        when(repository.insert(any(GraphRelease.class))).thenReturn(1);

        GraphRelease result = service.startValidation("project-1", "scanVersion-1");

        assertThat(result).isNotNull();
        assertThat(result.getProjectId()).isEqualTo("project-1");
        assertThat(result.getScanVersionId()).isEqualTo("scanVersion-1");
        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.VALIDATING.name());
        assertThat(result.getCreatedAt()).isNotNull();

        ArgumentCaptor<GraphRelease> captor = ArgumentCaptor.forClass(GraphRelease.class);
        verify(repository).insert(captor.capture());
        GraphRelease inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(GraphReleaseStatus.VALIDATING.name());
    }

    @Test
    void startValidation_returnsExistingRecord_whenAlreadyExists_idempotency() {
        GraphRelease existing = new GraphRelease();
        existing.setId("release-1");
        existing.setProjectId("project-1");
        existing.setScanVersionId("scanVersion-1");
        existing.setStatus(GraphReleaseStatus.VALIDATING.name());
        existing.setCreatedAt(LocalDateTime.now());
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(existing);

        GraphRelease result = service.startValidation("project-1", "scanVersion-1");

        assertThat(result).isSameAs(existing);
        assertThat(result.getId()).isEqualTo("release-1");
        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.VALIDATING.name());

        verify(repository, never()).insert(any(GraphRelease.class));
    }

    @Test
    void startValidation_returnsExistingPublishedRecord_whenAlreadyPublished_idempotency() {
        GraphRelease existing = new GraphRelease();
        existing.setId("release-pub");
        existing.setProjectId("project-1");
        existing.setScanVersionId("scanVersion-1");
        existing.setStatus(GraphReleaseStatus.PUBLISHED.name());
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(existing);

        GraphRelease result = service.startValidation("project-1", "scanVersion-1");

        assertThat(result).isSameAs(existing);
        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.PUBLISHED.name());
        verify(repository, never()).insert(any(GraphRelease.class));
    }

    @Test
    void startValidation_throws_whenFeatureDisabled() {
        config.setEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startValidation("project-1", "scanVersion-1"));

        assertThat(ex.getCode()).isEqualTo(6002);
        verify(repository, never()).findByProjectAndVersion(anyString(), anyString());
        verify(repository, never()).insert(any(GraphRelease.class));
    }

    // ========== markPublished ==========

    @Test
    void markPublished_transitionsFromValidatingToPublished_andSetsPublishedAt() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.VALIDATING);
        when(repository.selectById("release-1")).thenReturn(release);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        GraphRelease result = service.markPublished("release-1");

        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.PUBLISHED.name());
        assertThat(result.getPublishedAt()).isNotNull();

        verify(repository).updateById(release);
    }

    @Test
    void markPublished_throws_whenStatusIsDraft() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.DRAFT);
        when(repository.selectById("release-1")).thenReturn(release);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markPublished("release-1"));

        assertThat(ex.getCode()).isEqualTo(6001);
        verify(repository, never()).updateById(any(GraphRelease.class));
    }

    @Test
    void markPublished_throws_whenStatusIsFailed() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.FAILED);
        release.setFailureReasons(List.of("validation error"));
        when(repository.selectById("release-1")).thenReturn(release);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markPublished("release-1"));

        assertThat(ex.getCode()).isEqualTo(6001);
        verify(repository, never()).updateById(any(GraphRelease.class));
    }

    @Test
    void markPublished_throws_whenStatusIsAlreadyPublished() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.PUBLISHED);
        release.setPublishedAt(LocalDateTime.now());
        when(repository.selectById("release-1")).thenReturn(release);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markPublished("release-1"));

        assertThat(ex.getCode()).isEqualTo(6001);
        verify(repository, never()).updateById(any(GraphRelease.class));
    }

    @Test
    void markPublished_throws_whenReleaseNotFound() {
        when(repository.selectById("nonexistent")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markPublished("nonexistent"));

        assertThat(ex.getCode()).isEqualTo(6003);
        verify(repository, never()).updateById(any(GraphRelease.class));
    }

    // ========== markFailed ==========

    @Test
    void markFailed_setsStatusToFailed_andRecordsReasons() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.VALIDATING);
        when(repository.selectById("release-1")).thenReturn(release);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        List<String> reasons = List.of("schema mismatch", "missing table: t_order");
        GraphRelease result = service.markFailed("release-1", reasons);

        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.FAILED.name());
        assertThat(result.getFailureReasons()).containsExactly("schema mismatch", "missing table: t_order");

        verify(repository).updateById(release);
    }

    @Test
    void markFailed_canTransitionFromPublished_forRollback() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.PUBLISHED);
        release.setPublishedAt(LocalDateTime.now());
        when(repository.selectById("release-1")).thenReturn(release);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        GraphRelease result = service.markFailed("release-1", List.of("post-publish check failed"));

        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.FAILED.name());
        assertThat(result.getFailureReasons()).containsExactly("post-publish check failed");
    }

    @Test
    void markFailed_acceptsNullReasons() {
        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.VALIDATING);
        when(repository.selectById("release-1")).thenReturn(release);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        GraphRelease result = service.markFailed("release-1", null);

        assertThat(result.getStatus()).isEqualTo(GraphReleaseStatus.FAILED.name());
        assertThat(result.getFailureReasons()).isNull();
    }

    @Test
    void markFailed_throws_whenReleaseNotFound() {
        when(repository.selectById("nonexistent")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markFailed("nonexistent", List.of("error")));

        assertThat(ex.getCode()).isEqualTo(6003);
        verify(repository, never()).updateById(any(GraphRelease.class));
    }

    // ========== 完整状态机流程 ==========

    @Test
    void fullStateMachineFlow_startValidation_then_markPublished() {
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(null);
        when(repository.insert(any(GraphRelease.class))).thenReturn(1);

        GraphRelease created = service.startValidation("project-1", "scanVersion-1");
        assertThat(created.getStatus()).isEqualTo(GraphReleaseStatus.VALIDATING.name());

        when(repository.selectById(created.getId())).thenReturn(created);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        GraphRelease published = service.markPublished(created.getId());
        assertThat(published.getStatus()).isEqualTo(GraphReleaseStatus.PUBLISHED.name());
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    void fullStateMachineFlow_startValidation_then_markFailed() {
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(null);
        when(repository.insert(any(GraphRelease.class))).thenReturn(1);

        GraphRelease created = service.startValidation("project-1", "scanVersion-1");
        assertThat(created.getStatus()).isEqualTo(GraphReleaseStatus.VALIDATING.name());

        when(repository.selectById(created.getId())).thenReturn(created);
        when(repository.updateById(any(GraphRelease.class))).thenReturn(1);

        GraphRelease failed = service.markFailed(created.getId(), List.of("validation timeout"));
        assertThat(failed.getStatus()).isEqualTo(GraphReleaseStatus.FAILED.name());
        assertThat(failed.getFailureReasons()).containsExactly("validation timeout");
    }

    @Test
    void idempotency_secondStartValidationReturnsSameRecord_doesNotCreateSecond() {
        GraphRelease existing = new GraphRelease();
        existing.setId("release-1");
        existing.setProjectId("project-1");
        existing.setScanVersionId("scanVersion-1");
        existing.setStatus(GraphReleaseStatus.VALIDATING.name());
        when(repository.findByProjectAndVersion(anyString(), anyString())).thenReturn(existing);

        GraphRelease first = service.startValidation("project-1", "scanVersion-1");
        GraphRelease second = service.startValidation("project-1", "scanVersion-1");

        assertThat(second).isSameAs(first);
        verify(repository, times(2)).findByProjectAndVersion(anyString(), anyString());
        verify(repository, never()).insert(any(GraphRelease.class));
    }

    // ========== 辅助方法 ==========

    private GraphRelease buildRelease(String id, GraphReleaseStatus status) {
        GraphRelease release = new GraphRelease();
        release.setId(id);
        release.setProjectId("project-1");
        release.setScanVersionId("scanVersion-1");
        release.setStatus(status.name());
        release.setCreatedAt(LocalDateTime.now());
        return release;
    }
}
