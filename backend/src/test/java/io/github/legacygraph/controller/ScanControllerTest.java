package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    private final String testProjectId = "test-project-scan";

    @BeforeEach
    void setUp() {
        // Ensure a project exists in DB to satisfy FK constraints on lg_scan_version
        try {
            Project project = new Project();
            project.setId(testProjectId);
            project.setProjectCode("SCAN-TEST-PROJ");
            project.setProjectName("Scan Test Project");
            project.setRepoUrl("https://github.com/test/scan");
            project.setOwner("admin");
            project.setStatus("ACTIVE");
            projectRepository.insert(project);
        } catch (Exception e) {
            // already exists
        }
    }

    @Test
    void testCreateScanVersion_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetProgress_Success() throws Exception {
        // First create a version
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-progress");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertThat(root.get("code").asInt()).isEqualTo(0);
        String versionId = root.get("data").asText();

        mockMvc.perform(get("/lg/projects/{projectId}/scan-versions/{versionId}/progress", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionId").value(versionId));
    }

    @Test
    void testStartScan_Success() throws Exception {
        // First create a version
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-start");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertThat(root.get("code").asInt()).isEqualTo(0);
        String versionId = root.get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/start", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testPauseScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-pause");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertThat(root.get("code").asInt()).isEqualTo(0);
        String versionId = root.get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/pause", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testCancelScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-cancel");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertThat(root.get("code").asInt()).isEqualTo(0);
        String versionId = root.get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/cancel", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testResumeScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-resume");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertThat(root.get("code").asInt()).isEqualTo(0);
        String versionId = root.get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/resume", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
