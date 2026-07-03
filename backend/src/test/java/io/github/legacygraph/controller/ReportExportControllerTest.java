package io.github.legacygraph.controller;

import io.github.legacygraph.service.report.ReportExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportExportControllerTest {

    @Mock
    private ReportExportService reportExportService;

    private ReportExportController controller;

    @BeforeEach
    void setUp() {
        controller = new ReportExportController(reportExportService);
    }

    @Test
    void testExportMigrationReport_MD() {
        byte[] mockData = "# 迁移就绪度报告\nTest".getBytes();
        when(reportExportService.exportReport(eq("project-1"), isNull(),
                eq(ReportExportService.ReportType.MIGRATION_READINESS),
                eq(ReportExportService.ExportFormat.MD)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportMigrationReport("project-1", "MD");

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(mockData.length, response.getBody().length);
        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(contentDisposition);
        assertTrue(contentDisposition.contains(".md"), "文件名应以 .md 结尾");
    }

    @Test
    void testExportMigrationReport_PDF() {
        byte[] mockData = "%PDF-1.4 test".getBytes();
        when(reportExportService.exportReport(eq("project-1"), isNull(),
                eq(ReportExportService.ReportType.MIGRATION_READINESS),
                eq(ReportExportService.ExportFormat.PDF)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportMigrationReport("project-1", "PDF");

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE).contains("pdf"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains(".pdf"));
    }

    @Test
    void testExportConfidenceReport_Success() {
        byte[] mockData = "# 置信度趋势报告".getBytes();
        when(reportExportService.exportReport(eq("project-1"), eq("v1"),
                eq(ReportExportService.ReportType.CONFIDENCE_TREND),
                eq(ReportExportService.ExportFormat.MD)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportConfidenceReport("project-1", "v1", "MD");

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(mockData, response.getBody());
    }

    @Test
    void testExportTestCoverageReport_Success() {
        byte[] mockData = "# 测试覆盖率报告".getBytes();
        when(reportExportService.exportReport(eq("project-1"), eq("v1"),
                eq(ReportExportService.ReportType.TEST_COVERAGE),
                eq(ReportExportService.ExportFormat.MD)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportTestCoverageReport("project-1", "v1", "MD");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("# 测试覆盖率", new String(response.getBody()).substring(0, 7));
    }

    @Test
    void testExportGraphQualityReport_Success() {
        byte[] mockData = "# 图谱质量报告".getBytes();
        when(reportExportService.exportReport(eq("project-1"), eq("v1"),
                eq(ReportExportService.ReportType.GRAPH_QUALITY),
                eq(ReportExportService.ExportFormat.MD)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportGraphQualityReport("project-1", "v1", "MD");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testExportMigrationReport_InvalidFormatDefaultsToMD() {
        byte[] mockData = "default".getBytes();
        when(reportExportService.exportReport(eq("project-1"), isNull(),
                eq(ReportExportService.ReportType.MIGRATION_READINESS),
                eq(ReportExportService.ExportFormat.MD)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportMigrationReport("project-1", "INVALID");

        assertEquals(200, response.getStatusCode().value());
        verify(reportExportService, times(1)).exportReport(
                anyString(), isNull(), any(), eq(ReportExportService.ExportFormat.MD));
    }

    @Test
    void testExportConfidenceReport_Excel() {
        byte[] mockData = new byte[]{80, 75, 3, 4, 0, 0, 0, 0, 0}; // ZIP header for xlsx
        when(reportExportService.exportReport(eq("project-1"), eq("v1"),
                eq(ReportExportService.ReportType.CONFIDENCE_TREND),
                eq(ReportExportService.ExportFormat.EXCEL)))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportConfidenceReport("project-1", "v1", "EXCEL");

        assertEquals(200, response.getStatusCode().value());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains(".xlsx"), "Excel 文件应以 .xlsx 结尾");
    }

    @Test
    void testGetSupportedFormats() {
        ResponseEntity<java.util.Map<String, Object>> response = controller.getSupportedFormats();

        assertEquals(200, response.getStatusCode().value());
        java.util.Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("formats"));
        assertTrue(body.containsKey("reportTypes"));

        @SuppressWarnings("unchecked")
        java.util.List<String> formats = (java.util.List<String>) body.get("formats");
        assertTrue(formats.contains("MD"));
        assertTrue(formats.contains("PDF"));
        assertTrue(formats.contains("EXCEL"));
    }

    @Test
    void testExportResponse_ContentDisposition() {
        byte[] mockData = "content".getBytes();
        when(reportExportService.exportReport(anyString(), isNull(), any(), any()))
                .thenReturn(mockData);

        ResponseEntity<byte[]> response = controller.exportMigrationReport("project-1", "MD");

        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(contentDisposition);
        assertTrue(contentDisposition.contains("attachment"), "应为附件下载");
        assertTrue(contentDisposition.contains("filename*="), "应包含 UTF-8 编码的文件名");
    }
}
