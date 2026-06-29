package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.VectorDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VectorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final Long testProjectId = 1L;

    @Test
    void testBatchUpsert_Success() throws Exception {
        VectorDocument doc = new VectorDocument();
        doc.setContent("Test content for vectorization");
        doc.setChunkType("CODE");

        mockMvc.perform(post("/lg/projects/{projectId}/vector/upsert", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(doc))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testSemanticSearch_Success() throws Exception {
        String query = "How to handle user authentication";

        mockMvc.perform(post("/lg/projects/{projectId}/vector/search", testProjectId)
                        .param("topK", "10")
                        .param("chunkType", "CODE")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testSemanticSearch_DefaultTopK() throws Exception {
        String query = "Find similar code";

        mockMvc.perform(post("/lg/projects/{projectId}/vector/search", testProjectId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testFindSimilarNodes_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/vector/similar-nodes", testProjectId)
                        .param("nodeName", "UserService")
                        .param("threshold", "0.85"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testFindSimilarNodes_DefaultThreshold() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/vector/similar-nodes", testProjectId)
                        .param("nodeName", "AuthController"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
