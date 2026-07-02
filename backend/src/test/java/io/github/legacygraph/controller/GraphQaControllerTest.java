package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 知识库问答控制器集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class GraphQaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testAsk_ReturnsSuccess() throws Exception {
        Map<String, Object> request = Map.of(
                "projectId", "test-project",
                "versionId", "v1",
                "question", "这个项目有哪些Service类？"
        );

        mockMvc.perform(post("/qa/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testAsk_EmptyQuestion_ReturnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "projectId", "test-project",
                "question", ""
        );

        mockMvc.perform(post("/qa/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }

    @Test
    void testAsk_NullQuestion_ReturnsBadRequest() throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("projectId", "test-project");
        request.put("question", null);

        mockMvc.perform(post("/qa/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }

    @Test
    void testAsk_MissingQuestionField_ReturnsBadRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "projectId", "test-project"
        );

        mockMvc.perform(post("/qa/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }
}
