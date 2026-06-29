package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateScanVersionRequest;
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

    private final String testProjectId = "test-project-1";

    @Test
    void testCreateScanVersion_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetProgress_Success() throws Exception {
        // First create a version
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String versionId = objectMapper.readTree(result).get("data").asText();

        mockMvc.perform(get("/lg/projects/{projectId}/scan-versions/{versionId}/progress", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.versionId").value(versionId));
    }

    @Test
    void testStartScan_Success() throws Exception {
        // First create a version
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String versionId = objectMapper.readTree(result).get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/start", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testPauseScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String versionId = objectMapper.readTree(result).get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/pause", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testCancelScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String versionId = objectMapper.readTree(result).get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/cancel", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testResumeScan_Success() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String versionId = objectMapper.readTree(result).get("data").asText();

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/resume", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
