package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.trace.TraceIngestRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.repository.RuntimeTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 运行时链路控制器集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class TraceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RuntimeTraceRepository runtimeTraceRepository;

    private final String testProjectId = "test-project-trace";

    @BeforeEach
    void setUp() {
        runtimeTraceRepository.delete(new QueryWrapper<>());
        projectRepository.delete(new QueryWrapper<>());

        // 创建测试项目
        Project p = new Project();
        p.setId(testProjectId);
        p.setProjectCode("TRACE-TEST");
        p.setProjectName("Trace Test Project");
        p.setRepoUrl("https://github.com/test/trace");
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        projectRepository.insert(p);
    }

    @Test
    void testIngest_Success() throws Exception {
        TraceIngestRequest request = new TraceIngestRequest();
        request.setVersionId("v1");

        TraceIngestRequest.SpanDto span = new TraceIngestRequest.SpanDto();
        span.setTraceId("trace-001");
        span.setSpanId("span-001");
        span.setServiceName("user-service");
        span.setOperationName("GET /api/users");
        span.setSpanKind("SERVER");
        span.setDurationMs(150L);
        span.setStatus("OK");
        span.setStartEpochMs(System.currentTimeMillis());
        request.setSpans(List.of(span));

        mockMvc.perform(post("/lg/projects/{projectId}/runtime/traces", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ingested").value(1));
    }

    @Test
    void testIngest_EmptySpans() throws Exception {
        TraceIngestRequest request = new TraceIngestRequest();
        request.setVersionId("v1");
        request.setSpans(List.of());

        mockMvc.perform(post("/lg/projects/{projectId}/runtime/traces", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ingested").value(0));
    }

    @Test
    void testGetTopology_ReturnsSuccess() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/runtime/topology", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void testListTraces_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/runtime/traces", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testListTraces_WithData() throws Exception {
        // 先插入一条 trace 数据
        RuntimeTrace trace = new RuntimeTrace();
        trace.setProjectId(testProjectId);
        trace.setVersionId("v1");
        trace.setTraceId("trace-001");
        trace.setSpanId("span-001");
        trace.setServiceName("user-service");
        trace.setOperationName("GET /api/users");
        trace.setSpanKind("SERVER");
        trace.setDurationMs(150L);
        trace.setStatus("OK");
        trace.setCreatedAt(LocalDateTime.now());
        runtimeTraceRepository.insert(trace);

        mockMvc.perform(get("/lg/projects/{projectId}/runtime/traces", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
