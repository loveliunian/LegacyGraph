package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.PromptTemplate;
import io.github.legacygraph.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 提示词模板服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceTest {

    @Mock
    private PromptTemplateRepository promptTemplateRepository;

    @InjectMocks
    private PromptTemplateService promptTemplateService;

    @Test
    void testList_Empty() {
        Page<PromptTemplate> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(promptTemplateRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<PromptTemplate> result = promptTemplateService.list(1, 10, null, null, null);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testList_WithData() {
        List<PromptTemplate> templates = new ArrayList<>();
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setTemplateCode("code-review");
        template.setVersion("1.0");
        template.setScene("review");
        template.setIsActive(true);
        templates.add(template);

        Page<PromptTemplate> mockPage = new Page<>(1, 10);
        mockPage.setRecords(templates);
        mockPage.setTotal(1);

        when(promptTemplateRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<PromptTemplate> result = promptTemplateService.list(1, 10, null, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals("code-review", result.getList().get(0).getTemplateCode());
    }

    @Test
    void testList_WithFilters() {
        Page<PromptTemplate> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(promptTemplateRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<PromptTemplate> result = promptTemplateService.list(1, 10, "review", "review", "active");

        assertNotNull(result);
        verify(promptTemplateRepository).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void testListActive_ReturnsActiveTemplates() {
        List<PromptTemplate> templates = new ArrayList<>();
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setTemplateCode("active-code");
        template.setIsActive(true);
        templates.add(template);

        when(promptTemplateRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(templates);

        List<PromptTemplate> result = promptTemplateService.listActive();

        assertEquals(1, result.size());
        assertEquals("active-code", result.get(0).getTemplateCode());
    }

    @Test
    void testGetById_Found() {
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setTemplateCode("test-code");
        template.setVersion("1.0");

        when(promptTemplateRepository.selectById(1L)).thenReturn(template);

        PromptTemplate result = promptTemplateService.getById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("test-code", result.getTemplateCode());
    }

    @Test
    void testGetById_NotFound() {
        when(promptTemplateRepository.selectById(999L)).thenReturn(null);

        PromptTemplate result = promptTemplateService.getById(999L);

        assertNull(result);
    }

    @Test
    void testGetActiveByCode_Found() {
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setTemplateCode("test-code");
        template.setIsActive(true);

        when(promptTemplateRepository.findActiveByCode("test-code")).thenReturn(template);

        PromptTemplate result = promptTemplateService.getActiveByCode("test-code");

        assertNotNull(result);
        assertEquals("test-code", result.getTemplateCode());
    }

    @Test
    void testCreate_Success() {
        PromptTemplate template = new PromptTemplate();
        template.setTemplateCode("new-code");
        template.setScene("doc");
        template.setSystemPrompt("You are a helpful assistant.");
        template.setIsActive(true);

        when(promptTemplateRepository.insert(any(PromptTemplate.class))).thenReturn(1);

        PromptTemplate result = promptTemplateService.create(template);

        assertNotNull(result);
        assertEquals("new-code", result.getTemplateCode());
        assertEquals("1.0", result.getVersion()); // 默认版本
        assertNotNull(result.getCreatedAt());
        verify(promptTemplateRepository).insert(any(PromptTemplate.class));
    }

    @Test
    void testCreate_WithCustomVersion() {
        PromptTemplate template = new PromptTemplate();
        template.setTemplateCode("custom-code");
        template.setVersion("2.0");
        template.setScene("code");

        when(promptTemplateRepository.insert(any(PromptTemplate.class))).thenReturn(1);

        PromptTemplate result = promptTemplateService.create(template);

        assertNotNull(result);
        assertEquals("2.0", result.getVersion());
    }

    @Test
    void testUpdate_Success() {
        PromptTemplate existing = new PromptTemplate();
        existing.setId(1L);
        existing.setTemplateCode("update-code");
        existing.setVersion("1.0");
        existing.setScene("code");
        existing.setSystemPrompt("Old prompt");
        existing.setIsActive(true);

        PromptTemplate update = new PromptTemplate();
        update.setId(1L);
        update.setSystemPrompt("Updated prompt");

        when(promptTemplateRepository.selectById(1L)).thenReturn(existing);
        when(promptTemplateRepository.updateById(any(PromptTemplate.class))).thenReturn(1);
        when(promptTemplateRepository.insert(any(PromptTemplate.class))).thenReturn(1);

        PromptTemplate result = promptTemplateService.update(update);

        assertNotNull(result);
        assertEquals("update-code", result.getTemplateCode());
        assertEquals("1.1", result.getVersion()); // 版本递增
        assertTrue(result.getIsActive());
        // 旧版本应该被停用
        verify(promptTemplateRepository).updateById((PromptTemplate) any());
        // 新版本应该被创建
        verify(promptTemplateRepository).insert(any(PromptTemplate.class));
    }

    @Test
    void testUpdate_NotFound() {
        PromptTemplate update = new PromptTemplate();
        update.setId(999L);
        update.setSystemPrompt("Updated prompt");

        when(promptTemplateRepository.selectById(999L)).thenReturn(null);

        PromptTemplate result = promptTemplateService.update(update);

        assertNull(result);
        verify(promptTemplateRepository, never()).insert(any(PromptTemplate.class));
    }

    @Test
    void testToggleActive_Success() {
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setTemplateCode("toggle-code");
        template.setIsActive(true);

        when(promptTemplateRepository.selectById(1L)).thenReturn(template);
        when(promptTemplateRepository.updateById(any(PromptTemplate.class))).thenReturn(1);

        boolean result = promptTemplateService.toggleActive(1L);

        assertTrue(result);
        assertFalse(template.getIsActive());
        verify(promptTemplateRepository).updateById(template);
    }

    @Test
    void testToggleActive_NotFound() {
        when(promptTemplateRepository.selectById(999L)).thenReturn(null);

        boolean result = promptTemplateService.toggleActive(999L);

        assertFalse(result);
        verify(promptTemplateRepository, never()).updateById(any(PromptTemplate.class));
    }

    @Test
    void testDelete_Success() {
        when(promptTemplateRepository.deleteById(1L)).thenReturn(1);

        boolean result = promptTemplateService.delete(1L);

        assertTrue(result);
        verify(promptTemplateRepository).deleteById(1L);
    }

    @Test
    void testDelete_NotFound() {
        when(promptTemplateRepository.deleteById(999L)).thenReturn(0);

        boolean result = promptTemplateService.delete(999L);

        assertFalse(result);
    }

    @Test
    void testEvictCache() {
        // 缓存驱逐操作应该不抛出异常
        assertDoesNotThrow(() -> promptTemplateService.evictCache());
    }
}
