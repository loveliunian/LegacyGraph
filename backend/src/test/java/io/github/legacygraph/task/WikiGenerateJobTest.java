package io.github.legacygraph.task;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4-T5: WikiGenerateJob 状态判定测试。
 * 验证：
 * - 仅有 SUCCESS 状态的 ScanVersion 被处理
 * - 无 Wiki 报告时插入新报告
 * - 图谱节点/边数量有变化时重新生成
 * - 节点/边数量未变时跳过（不重复生成）
 */
class WikiGenerateJobTest {

    private ScanVersionRepository scanRepo;
    private ReportRepository reportRepo;
    private Neo4jGraphDao dao;
    private WikiGenerateJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scanRepo = mock(ScanVersionRepository.class);
        reportRepo = mock(ReportRepository.class);
        dao = mock(Neo4jGraphDao.class);
        job = new WikiGenerateJob(scanRepo, reportRepo, dao);
        ReflectionTestUtils.setField(job, "reportsLocalDir", System.getProperty("java.io.tmpdir") + "/wiki-test");

        // 为 ScanVersionRepository 和 ReportRepository 各准备一个 LambdaQueryChainWrapper mock
        LambdaQueryChainWrapper<ScanVersion> scanChain = (LambdaQueryChainWrapper<ScanVersion>) mock(LambdaQueryChainWrapper.class);
        LambdaQueryChainWrapper<Report> reportChain = (LambdaQueryChainWrapper<Report>) mock(LambdaQueryChainWrapper.class);

        when(scanRepo.lambdaQuery()).thenReturn(scanChain);
        when(reportRepo.lambdaQuery()).thenReturn(reportChain);
        // 让 chain 上的方法返回自己以便链式调用 — 用 thenAnswer 而非 thenReturn，避免重载歧义
        org.mockito.Mockito.doAnswer(inv -> scanChain).when(scanChain).eq(any(), any());
        org.mockito.Mockito.doAnswer(inv -> scanChain).when(scanChain).orderByDesc((com.baomidou.mybatisplus.core.toolkit.support.SFunction) any());
        org.mockito.Mockito.doAnswer(inv -> scanChain).when(scanChain).orderByDesc(java.util.List.of());
        org.mockito.Mockito.doAnswer(inv -> scanChain).when(scanChain).last(any());
        org.mockito.Mockito.doAnswer(inv -> reportChain).when(reportChain).eq(any(), any());
        org.mockito.Mockito.doAnswer(inv -> reportChain).when(reportChain).orderByDesc((com.baomidou.mybatisplus.core.toolkit.support.SFunction) any());
        org.mockito.Mockito.doAnswer(inv -> reportChain).when(reportChain).orderByDesc(java.util.List.of());
        org.mockito.Mockito.doAnswer(inv -> reportChain).when(reportChain).last(any());
    }

    @Test
    void generateWikiForCompletedScans_noSuccessVersions_doesNothing() {
        when(scanRepo.lambdaQuery().list()).thenReturn(List.of());

        assertDoesNotThrow(() -> job.generateWikiForCompletedScans());

        verify(reportRepo, never()).insert(any(Report.class));
    }

    @Test
    void generateWikiForCompletedScans_noExistingWiki_insertsNew() {
        ScanVersion v = newVersion();

        when(scanRepo.lambdaQuery().list()).thenReturn(List.of(v));
        when(reportRepo.lambdaQuery().one()).thenReturn(null); // 无 Wiki
        when(dao.countNodes(eq("p1"), eq("v1"), any())).thenReturn(100L);
        when(dao.countEdges(eq("p1"), eq("v1"), any())).thenReturn(200L);
        when(dao.versionGraphStats(eq("p1"), eq("v1"))).thenReturn(Map.of());
        when(dao.nodeTypeDistribution(eq("p1"), eq("v1"))).thenReturn(List.of());
        when(reportRepo.lambdaQuery().list()).thenReturn(List.of());

        job.generateWikiForCompletedScans();

        verify(reportRepo).insert(any(Report.class));
    }

    @Test
    void generateWikiForCompletedScans_unchangedStats_skipsInsert() {
        ScanVersion v = newVersion();
        v.setNodeCount(100L);
        v.setEdgeCount(200L);

        Report existing = new Report();
        existing.setId("wiki-1");
        existing.setReportType("WIKI");
        existing.setStatus("COMPLETED");

        when(scanRepo.lambdaQuery().list()).thenReturn(List.of(v));
        when(reportRepo.lambdaQuery().one()).thenReturn(existing); // 已有 Wiki
        when(dao.countNodes(eq("p1"), eq("v1"), any())).thenReturn(100L);
        when(dao.countEdges(eq("p1"), eq("v1"), any())).thenReturn(200L);

        job.generateWikiForCompletedScans();

        // 节点/边未变 → 不重新生成
        verify(reportRepo, never()).insert(any(Report.class));
    }

    @Test
    void generateWikiForCompletedScans_changedNodeCount_regenerates() {
        ScanVersion v = newVersion();
        v.setNodeCount(100L);
        v.setEdgeCount(200L);

        Report existing = new Report();
        existing.setId("wiki-old");
        existing.setReportType("WIKI");
        existing.setStatus("COMPLETED");

        when(scanRepo.lambdaQuery().list()).thenReturn(List.of(v));
        // 第一次 one() 返回已存在 Wiki（needsWikiRegeneration 判断用）
        when(reportRepo.lambdaQuery().one()).thenReturn(existing);
        // 第二次 list() 返回旧 Wiki（删除用）
        when(reportRepo.lambdaQuery().list()).thenReturn(List.of(existing));
        // 节点数变了
        when(dao.countNodes(eq("p1"), eq("v1"), any())).thenReturn(150L);
        when(dao.countEdges(eq("p1"), eq("v1"), any())).thenReturn(250L);
        when(dao.versionGraphStats(eq("p1"), eq("v1"))).thenReturn(Map.of());
        when(dao.nodeTypeDistribution(eq("p1"), eq("v1"))).thenReturn(List.of());

        job.generateWikiForCompletedScans();

        verify(reportRepo).deleteById(eq("wiki-old"));
        verify(reportRepo).insert(any(Report.class));
    }

    @Test
    void generateWikiForCompletedScans_dbQueryFailure_doesNotThrow() {
        when(scanRepo.lambdaQuery()).thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() -> job.generateWikiForCompletedScans());

        verify(reportRepo, never()).insert(any(Report.class));
    }

    private static ScanVersion newVersion() {
        ScanVersion v = new ScanVersion();
        v.setId("v1");
        v.setProjectId("p1");
        v.setScanStatus("SUCCESS");
        v.setNodeCount(100L);
        v.setEdgeCount(200L);
        return v;
    }
}