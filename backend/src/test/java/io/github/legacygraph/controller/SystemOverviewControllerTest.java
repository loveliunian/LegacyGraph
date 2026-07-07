package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.service.systemoverview.SystemOverviewDocumentService;
import io.github.legacygraph.service.systemoverview.SystemOverviewService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemOverviewControllerTest {

    @Test
    void generateReport_persistsSystemOverviewDocumentForExistingScan() throws Exception {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        SystemOverviewDocumentService documentService = mock(SystemOverviewDocumentService.class);
        SystemOverviewController controller = new SystemOverviewController(systemOverviewService, documentService);
        Report report = new Report();
        report.setId("report-1");
        report.setProjectId("margin-project");
        report.setVersionId("scan-v1");
        report.setReportType("SYSTEM_OVERVIEW");
        when(documentService.generateAfterScan("margin-project", "scan-v1")).thenReturn(report);

        Result<Report> result = controller.generateReport("margin-project", "scan-v1");

        assertEquals(0, result.getCode());
        assertSame(report, result.getData());
        verify(documentService).generateAfterScan("margin-project", "scan-v1");
    }
}
