package io.github.legacygraph.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.ScanTaskStatus;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.KnowledgeClaimRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanResearchReportServiceTest {

    @Mock
    private ScanVersionRepository scanVersionRepository;

    @Mock
    private ScanTaskRepository scanTaskRepository;

    @Mock
    private KnowledgeClaimRepository claimRepository;

    private ScanResearchReportService service;

    @BeforeEach
    void setUp() {
        service = new ScanResearchReportService(scanVersionRepository, scanTaskRepository, claimRepository);
    }

    @Test
    void generateMarkdown_withFullData_containsAllSections() {
        // given
        ScanVersion version = new ScanVersion();
        version.setId("v1");
        version.setScanStatus("COMPLETED");
        version.setStartedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        version.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 30));
        version.setNodeCount(100L);
        version.setEdgeCount(200L);
        version.setFactCount(50L);
        version.setTaskTotal(5);
        version.setTaskSuccess(4);
        version.setTaskFailed(1);

        ScanTask task = new ScanTask();
        task.setTaskType("JAVA_SCAN");
        task.setTaskStatus(ScanTaskStatus.SUCCESS.name());
        task.setOutputSummary("扫描完成");

        KnowledgeClaim claim1 = new KnowledgeClaim();
        claim1.setStatus("CONFIRMED");
        claim1.setSourceType("CODE");
        KnowledgeClaim claim2 = new KnowledgeClaim();
        claim2.setStatus("PENDING_CONFIRM");
        claim2.setSourceType("DOC_AI");

        when(scanVersionRepository.selectById("v1")).thenReturn(version);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(claimRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(claim1, claim2));

        // when
        String markdown = service.generateMarkdown("p1", "v1");

        // then
        assertNotNull(markdown);
        assertTrue(markdown.contains("# 资料扫描与图谱构建研究报告"));
        assertTrue(markdown.contains("## 1. 扫描输入"));
        assertTrue(markdown.contains("## 2. Adapter 执行统计"));
        assertTrue(markdown.contains("## 3. 数据库扫描统计"));
        assertTrue(markdown.contains("## 4. 图谱写入统计"));
        assertTrue(markdown.contains("## 5. Claim 统计"));
        assertTrue(markdown.contains("## 6. 三类图谱覆盖"));
        assertTrue(markdown.contains("## 7. 缺口清单"));
        assertTrue(markdown.contains("## 8. 不确定性声明"));

        // 验证数据正确嵌入
        assertTrue(markdown.contains("COMPLETED"));
        assertTrue(markdown.contains("JAVA_SCAN"));
        assertTrue(markdown.contains("节点数: 100"));
        assertTrue(markdown.contains("边数: 200"));
    }

    @Test
    void generateMarkdown_noVersion_handlesGracefully() {
        when(scanVersionRepository.selectById("v1")).thenReturn(null);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(claimRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        String markdown = service.generateMarkdown("p1", "v1");

        assertNotNull(markdown);
        assertTrue(markdown.contains("# 资料扫描与图谱构建研究报告"));
        assertTrue(markdown.contains("无扫描任务记录"));
        assertTrue(markdown.contains("无 Claim 记录"));
    }

    @Test
    void generateMarkdown_noTasks_showsNoTasksMessage() {
        ScanVersion version = new ScanVersion();
        version.setId("v1");
        when(scanVersionRepository.selectById("v1")).thenReturn(version);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(claimRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        String markdown = service.generateMarkdown("p1", "v1");

        assertTrue(markdown.contains("无扫描任务记录"));
    }

    @Test
    void generateMarkdown_noClaims_showsNoClaimsMessage() {
        ScanVersion version = new ScanVersion();
        version.setId("v1");
        when(scanVersionRepository.selectById("v1")).thenReturn(version);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(claimRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        String markdown = service.generateMarkdown("p1", "v1");

        assertTrue(markdown.contains("无 Claim 记录"));
        assertTrue(markdown.contains("无缺口记录"));
    }

    @Test
    void generateMarkdown_aiClaims_calculatesPercentages() {
        ScanVersion version = new ScanVersion();
        version.setId("v1");
        when(scanVersionRepository.selectById("v1")).thenReturn(version);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        KnowledgeClaim ai1 = new KnowledgeClaim();
        ai1.setStatus("PENDING_CONFIRM");
        ai1.setSourceType("DOC_AI");
        KnowledgeClaim code1 = new KnowledgeClaim();
        code1.setStatus("CONFIRMED");
        code1.setSourceType("CODE");

        when(claimRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(ai1, code1));

        String markdown = service.generateMarkdown("p1", "v1");

        assertTrue(markdown.contains("50.0%"));  // AI 候选比例
        assertTrue(markdown.contains("AI候选待确认: 1"));
    }

    @Test
    void generateMarkdown_containsProjectAndVersionInfo() {
        when(scanVersionRepository.selectById("v1")).thenReturn(null);
        when(scanTaskRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(claimRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        String markdown = service.generateMarkdown("my-project-id", "v1");

        assertTrue(markdown.contains("项目ID: my-project-id"));
        assertTrue(markdown.contains("版本ID: v1"));
    }
}
