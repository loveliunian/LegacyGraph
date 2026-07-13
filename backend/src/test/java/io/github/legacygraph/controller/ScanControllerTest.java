package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
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

    @Autowired
    private ScanVersionRepository scanVersionRepository;

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

    /**
     * 测试取消扫描：验证 API 返回成功、DB 状态更新为 CANCELLED。
     */
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

        // 验证取消 API 返回成功
        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/cancel", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 验证 DB 中状态为 CANCELLED
        ScanVersion version = scanVersionRepository.getById(versionId);
        Assertions.assertThat(version).isNotNull();
        Assertions.assertThat(version.getScanStatus()).isEqualTo("CANCELLED");
    }

    /**
     * 测试高速并发取消：多线程同时取消同一版本不应报错。
     */
    @Test
    void testCancelScan_ConcurrentSameVersion_NoError() throws Exception {
        CreateScanVersionRequest request = new CreateScanVersionRequest();
        request.setVersionNo("v1.0-cancel-concurrent");

        String result = mockMvc.perform(post("/lg/projects/{projectId}/scan-versions", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(result);
        String versionId = root.get("data").asText();

        // 模拟 10 个线程同时调用取消（重复取消应为幂等）
        Thread[] threads = new Thread[10];
        Exception[] errors = new Exception[10];
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/cancel",
                            testProjectId, versionId))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    errors[idx] = e;
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        for (Exception e : errors) {
            Assertions.assertThat(e).isNull();
        }

        // 并发取消后状态仍为 CANCELLED
        ScanVersion version = scanVersionRepository.getById(versionId);
        Assertions.assertThat(version.getScanStatus()).isEqualTo("CANCELLED");
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

        // resume 要求版本状态为 PAUSED 或 FAILED，先更新状态
        ScanVersion version = scanVersionRepository.getById(versionId);
        version.setScanStatus("PAUSED");
        scanVersionRepository.updateById(version);

        mockMvc.perform(post("/lg/projects/{projectId}/scan-versions/{versionId}/resume", testProjectId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
