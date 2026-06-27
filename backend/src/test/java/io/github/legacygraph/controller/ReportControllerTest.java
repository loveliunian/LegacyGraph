package io.github.legacygraph.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final String testProjectId = "test-project-1";

    @Test
    void testListReports_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/reports/list", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGenerateMigrationReport_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/migration-readiness/generate", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGenerateConfidenceTrend_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/confidence-trend/generate", testProjectId)
                        .param("versionId", "version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGenerateTestCoverage_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/test-coverage/generate", testProjectId)
                        .param("versionId", "version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGenerateGraphQuality_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/graph-quality/generate", testProjectId)
                        .param("versionId", "version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testDownloadReport_JsonFormat() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/reports/{reportId}/download", testProjectId, "report-1")
                        .param("format", "JSON"))
                .andExpect(status().isOk());
    }

    @Test
    void testDownloadReport_PdfFormat() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/reports/{reportId}/download", testProjectId, "report-1")
                        .param("format", "PDF"))
                .andExpect(status().isOk());
    }
}
