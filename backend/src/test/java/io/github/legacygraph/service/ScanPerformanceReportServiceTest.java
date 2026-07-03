package io.github.legacygraph.service;

import io.github.legacygraph.service.report.ScanPerformanceReportService;

import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanPerformanceReportServiceTest {

    @Test
    void generateMarkdown_includesPhaseDurationsAndWarningSummary() {
        ScanVersionRepository scanVersionRepository = mock(ScanVersionRepository.class);
        ScanTaskRepository scanTaskRepository = mock(ScanTaskRepository.class);
        ScanPerformanceReportService service = new ScanPerformanceReportService(
                scanVersionRepository, scanTaskRepository);

        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setVersionNo("scan-test");
        version.setScanStatus("SUCCESS");
        version.setScanScope("{\"scanTypes\":[\"CODE_SCAN\"]}");
        version.setStartedAt(LocalDateTime.of(2026, 7, 3, 10, 0));
        version.setFinishedAt(LocalDateTime.of(2026, 7, 3, 10, 2));

        ScanTask adapterTask = new ScanTask();
        adapterTask.setTaskType("ADAPTER_SCAN");
        adapterTask.setTaskStatus("SUCCESS");
        adapterTask.setTotalItems(20);
        adapterTask.setProcessedItems(20);
        adapterTask.setStartedAt(LocalDateTime.of(2026, 7, 3, 10, 0));
        adapterTask.setFinishedAt(LocalDateTime.of(2026, 7, 3, 10, 1));

        ScanTask aiTask = new ScanTask();
        aiTask.setTaskType("AI_ORCHESTRATION");
        aiTask.setTaskStatus("WARNING");
        aiTask.setOutputSummary("AI skipped");
        aiTask.setStartedAt(LocalDateTime.of(2026, 7, 3, 10, 1));
        aiTask.setFinishedAt(LocalDateTime.of(2026, 7, 3, 10, 2));

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.selectList(any())).thenReturn(List.of(adapterTask, aiTask));

        String markdown = service.generateMarkdown("project-1", "version-1");

        assertTrue(markdown.contains("# 扫描性能报告"));
        assertTrue(markdown.contains("**总耗时**: 2m0s"));
        assertTrue(markdown.contains("| ADAPTER_SCAN | SUCCESS | 20 | 20 |"));
        assertTrue(markdown.contains("- 警告数: 1"));
        assertTrue(markdown.contains("AI_ORCHESTRATION"));
        assertTrue(markdown.contains("AI skipped"));
    }
}
