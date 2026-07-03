package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.legacygraph.service.system.LlmProviderService;

/**
 * LLM 提供商管理控制器集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class LlmProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 测试环境使用 H2TestConfig 中 Mock 的 LlmProviderService 和 LlmGateway
        // 无需额外清理
    }

    @Test
    void testListAll_ReturnsSuccess() throws Exception {
        mockMvc.perform(get("/llm/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetByCode_NotFound() throws Exception {
        mockMvc.perform(get("/llm/providers/{providerCode}", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("提供商不存在: nonexistent"));
    }

    @Test
    void testGetDefault_ReturnsErrorWhenNoProvider() throws Exception {
        mockMvc.perform(get("/llm/providers/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("没有可用的 LLM 提供商"));
    }

    @Test
    void testSave_ReturnsSuccess() throws Exception {
        LlmProvider provider = new LlmProvider();
        provider.setProviderCode("test-provider");
        provider.setModelId("gpt-4");
        provider.setEndpoint("https://api.openai.com/v1");
        provider.setDeploymentMode("cloud");
        provider.setIsActive(true);

        Map<String, Object> apiConfig = new HashMap<>();
        apiConfig.put("api_key", "test-key");
        apiConfig.put("temperature", 0.7);
        provider.setApiConfig(apiConfig);

        mockMvc.perform(post("/llm/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(provider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testSetDefault_ReturnsSuccess() throws Exception {
        mockMvc.perform(put("/llm/providers/{providerCode}/set-default", "test-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testToggleActive_Enable() throws Exception {
        mockMvc.perform(put("/llm/providers/{providerCode}/toggle-active", "test-provider")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testToggleActive_Disable() throws Exception {
        mockMvc.perform(put("/llm/providers/{providerCode}/toggle-active", "test-provider")
                        .param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDelete_ReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/llm/providers/{providerCode}", "test-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
