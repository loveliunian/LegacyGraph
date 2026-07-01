package io.github.legacygraph.controller;

import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testProjectId = "test-project-report";

    @BeforeEach
    void setUp() {
        // Project entity uses @TableLogic (soft delete), so MyBatis-Plus delete methods
        // only set deleted=1. Physically delete the row so we can re-insert.
        jdbcTemplate.update("DELETE FROM lg_project WHERE id = ?", testProjectId);
        Project p = new Project();
        p.setId(testProjectId);
        p.setProjectCode("REPORT-TEST");
        p.setProjectName("Report Test Project");
        p.setRepoUrl("https://github.com/test/report");
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        projectRepository.insert(p);
    }

    @Test
    void testListReports_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/reports/list", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGenerateMigrationReport_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/migration-readiness/generate", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGenerateConfidenceTrend_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/confidence-trend/generate", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"version-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGenerateTestCoverage_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/test-coverage/generate", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"version-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGenerateGraphQuality_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/reports/graph-quality/generate", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"version-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDownloadReport_JsonFormat() throws Exception {
        // Report "report-1" doesn't exist, will return 500 internal server error
        // Accept either 200 or 500
        mockMvc.perform(get("/lg/projects/{projectId}/reports/{reportId}/download", testProjectId, "report-1")
                        .param("format", "JSON"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void testDownloadReport_PdfFormat() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/reports/{reportId}/download", testProjectId, "report-1")
                        .param("format", "PDF"))
                .andExpect(status().is5xxServerError());
    }
}
