package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.ReviewConfirmRequest;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.repository.ReviewRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReviewRecordRepository reviewRecordRepository;

    private final String projectId = "test-project";

    @BeforeEach
    void setUp() {
        reviewRecordRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testListPendingReviews() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/reviews")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void testListPendingReviews_WithFilters() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/reviews")
                        .param("targetType", "NODE")
                        .param("graphType", "CODE")
                        .param("minConfidence", "0.5")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testListReviewHistory() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/reviews/history")
                        .param("status", "APPROVED")
                        .param("reviewedBy", "admin")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void testConfirmReview_Success() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("NODE");
        request.setTargetId("test-node-1");
        request.setComment("确认无误");

        mockMvc.perform(post("/lg/projects/" + projectId + "/reviews/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testConfirmReview_MissingTargetId() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("NODE");
        request.setComment("确认无误");

        mockMvc.perform(post("/lg/projects/" + projectId + "/reviews/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testRejectReview_Success() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("EDGE");
        request.setTargetId("test-edge-1");
        request.setComment("需要重新分析");

        mockMvc.perform(post("/lg/projects/" + projectId + "/reviews/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testBatchConfirm_Success() throws Exception {
        List<String> ids = List.of("record-1", "record-2", "record-3");

        mockMvc.perform(post("/lg/projects/" + projectId + "/reviews/batch-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .queryParam("comment", "批量确认")
                        .content(objectMapper.writeValueAsString(ids)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetReviewDetail_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/reviews/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
