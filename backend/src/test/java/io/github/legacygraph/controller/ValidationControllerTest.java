package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.ReviewConfirmRequest;
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
class ValidationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGetReport_Success() throws Exception {
        mockMvc.perform(get("/lg/validation/report/{versionId}", "version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testUpdateConfidence_Success() throws Exception {
        mockMvc.perform(post("/lg/validation/update-confidence/{versionId}", "version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testConfirmNode_Confirmed() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("NODE");
        request.setTargetId("node-1");
        request.setReviewStatus("CONFIRMED");

        mockMvc.perform(post("/lg/validation/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testConfirmNode_Rejected() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("NODE");
        request.setTargetId("node-1");
        request.setReviewStatus("REJECTED");

        mockMvc.perform(post("/lg/validation/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testConfirmEdge_Confirmed() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("EDGE");
        request.setTargetId("edge-1");
        request.setReviewStatus("CONFIRMED");

        mockMvc.perform(post("/lg/validation/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testConfirmEdge_Rejected() throws Exception {
        ReviewConfirmRequest request = new ReviewConfirmRequest();
        request.setTargetType("EDGE");
        request.setTargetId("edge-1");
        request.setReviewStatus("REJECTED");

        mockMvc.perform(post("/lg/validation/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
