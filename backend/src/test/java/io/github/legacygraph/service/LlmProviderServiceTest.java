package io.github.legacygraph.service;

import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.repository.LlmProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.LlmProviderService;

/**
 * LLM 提供商管理服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    @Mock
    private LlmProviderRepository llmProviderRepository;

    @InjectMocks
    private LlmProviderService llmProviderService;

    @Test
    void testListAll_ReturnsList() {
        List<LlmProvider> providers = new ArrayList<>();
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setProviderCode("openai");
        provider.setModelId("gpt-4");
        provider.setIsActive(true);
        providers.add(provider);

        when(llmProviderRepository.findAll()).thenReturn(providers);

        List<LlmProvider> result = llmProviderService.listAll();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("openai", result.get(0).getProviderCode());
    }

    @Test
    void testListAll_Empty() {
        when(llmProviderRepository.findAll()).thenReturn(List.of());

        List<LlmProvider> result = llmProviderService.listAll();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetByCode_Found() {
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setProviderCode("deepseek");
        provider.setModelId("deepseek-chat");

        when(llmProviderRepository.findByCode("deepseek")).thenReturn(provider);

        LlmProvider result = llmProviderService.getByCode("deepseek");

        assertNotNull(result);
        assertEquals("deepseek", result.getProviderCode());
        assertEquals("deepseek-chat", result.getModelId());
    }

    @Test
    void testGetByCode_NotFound() {
        when(llmProviderRepository.findByCode("nonexistent")).thenReturn(null);

        LlmProvider result = llmProviderService.getByCode("nonexistent");

        assertNull(result);
    }

    @Test
    void testGetActiveDefault_Found() {
        LlmProvider defaultProvider = new LlmProvider();
        defaultProvider.setId(1L);
        defaultProvider.setProviderCode("openai");
        defaultProvider.setIsDefault(true);
        defaultProvider.setIsActive(true);

        when(llmProviderRepository.findAll()).thenReturn(List.of(defaultProvider));

        LlmProvider result = llmProviderService.getActiveDefault();

        assertNotNull(result);
        assertEquals("openai", result.getProviderCode());
    }

    @Test
    void testGetActiveDefault_NoneActive() {
        when(llmProviderRepository.findAll()).thenReturn(List.of());

        LlmProvider result = llmProviderService.getActiveDefault();

        assertNull(result);
    }

    @Test
    void testSave_New() {
        LlmProvider provider = new LlmProvider();
        provider.setProviderCode("new-provider");
        provider.setModelId("gpt-4");
        provider.setEndpoint("https://api.openai.com/v1");

        Map<String, Object> apiConfig = new HashMap<>();
        apiConfig.put("api_key", "test-key");
        provider.setApiConfig(apiConfig);

        when(llmProviderRepository.findByCode("new-provider")).thenReturn(null);
        when(llmProviderRepository.insert(any(LlmProvider.class))).thenReturn(1);

        LlmProvider result = llmProviderService.save(provider);

        assertNotNull(result);
        assertEquals("new-provider", result.getProviderCode());
        verify(llmProviderRepository).insert(any(LlmProvider.class));
        verify(llmProviderRepository, never()).updateById(any(LlmProvider.class));
    }

    @Test
    void testSave_Update() {
        LlmProvider existing = new LlmProvider();
        existing.setId(1L);
        existing.setProviderCode("openai");

        LlmProvider provider = new LlmProvider();
        provider.setProviderCode("openai");
        provider.setModelId("gpt-4o");

        when(llmProviderRepository.findByCode("openai")).thenReturn(existing);
        when(llmProviderRepository.updateById(any(LlmProvider.class))).thenReturn(1);

        LlmProvider result = llmProviderService.save(provider);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(llmProviderRepository).updateById(any(LlmProvider.class));
        verify(llmProviderRepository, never()).insert(any(LlmProvider.class));
    }

    @Test
    void testSetDefault_Success() {
        LlmProvider oldDefault = new LlmProvider();
        oldDefault.setId(1L);
        oldDefault.setProviderCode("openai");
        oldDefault.setIsDefault(true);

        LlmProvider newTarget = new LlmProvider();
        newTarget.setId(2L);
        newTarget.setProviderCode("deepseek");
        newTarget.setIsDefault(false);

        when(llmProviderRepository.findAll()).thenReturn(List.of(oldDefault, newTarget));
        when(llmProviderRepository.findByCode("deepseek")).thenReturn(newTarget);
        when(llmProviderRepository.updateById(any(LlmProvider.class))).thenReturn(1);

        llmProviderService.setDefault("deepseek");

        // 验证旧默认被取消
        verify(llmProviderRepository, atLeastOnce()).updateById(any(LlmProvider.class));
        assertTrue(newTarget.getIsDefault());
        assertTrue(newTarget.getIsActive());
    }

    @Test
    void testToggleActive_Enable() {
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setProviderCode("openai");
        provider.setIsActive(false);

        when(llmProviderRepository.findByCode("openai")).thenReturn(provider);
        when(llmProviderRepository.updateById(any(LlmProvider.class))).thenReturn(1);

        llmProviderService.toggleActive("openai", true);

        assertTrue(provider.getIsActive());
        verify(llmProviderRepository).updateById(any(LlmProvider.class));
    }

    @Test
    void testToggleActive_Disable() {
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setProviderCode("openai");
        provider.setIsActive(true);

        when(llmProviderRepository.findByCode("openai")).thenReturn(provider);
        when(llmProviderRepository.updateById(any(LlmProvider.class))).thenReturn(1);

        llmProviderService.toggleActive("openai", false);

        assertFalse(provider.getIsActive());
        verify(llmProviderRepository).updateById(any(LlmProvider.class));
    }

    @Test
    void testDelete_Success() {
        LlmProvider provider = new LlmProvider();
        provider.setId(1L);
        provider.setProviderCode("openai");

        when(llmProviderRepository.findByCode("openai")).thenReturn(provider);
        when(llmProviderRepository.deleteById((LlmProvider) any())).thenReturn(1);

        llmProviderService.delete("openai");

        verify(llmProviderRepository).deleteById((LlmProvider) any());
    }

    @Test
    void testDelete_NotFound() {
        when(llmProviderRepository.findByCode("nonexistent")).thenReturn(null);

        llmProviderService.delete("nonexistent");

        verify(llmProviderRepository, never()).deleteById((LlmProvider) any());
    }
}
