package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.SysConfig;
import io.github.legacygraph.repository.SysConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    @Mock
    private SysConfigRepository sysConfigRepository;

    @InjectMocks
    private SysConfigService sysConfigService;

    private SysConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new SysConfig();
        testConfig.setId("config-1");
        testConfig.setConfigKey("llm.api.key");
        testConfig.setConfigName("LLM API Key");
        testConfig.setConfigValue("test-api-key");
        testConfig.setConfigType("STRING");
        testConfig.setStatus("ACTIVE");
    }

    @Test
    void testList_WithoutKeywordOrStatus() {
        Page<SysConfig> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(testConfig));
        mockPage.setTotal(1);

        when(sysConfigRepository.selectPage(any(Page.class), any())).thenReturn(mockPage);

        PageResult<SysConfig> result = sysConfigService.list(1, 10, null, null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("llm.api.key", result.getList().get(0).getConfigKey());
    }

    @Test
    void testList_WithKeyword() {
        Page<SysConfig> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(testConfig));
        mockPage.setTotal(1);

        when(sysConfigRepository.selectPage(any(Page.class), any())).thenReturn(mockPage);

        PageResult<SysConfig> result = sysConfigService.list(1, 10, "llm", null);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
    }

    @Test
    void testList_WithStatus() {
        Page<SysConfig> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(testConfig));
        mockPage.setTotal(1);

        when(sysConfigRepository.selectPage(any(Page.class), any())).thenReturn(mockPage);

        PageResult<SysConfig> result = sysConfigService.list(1, 10, null, "ACTIVE");

        assertNotNull(result);
        assertEquals(1, result.getTotal());
    }

    @Test
    void testGetById_Found() {
        when(sysConfigRepository.selectById("config-1")).thenReturn(testConfig);

        SysConfig result = sysConfigService.getById("config-1");

        assertNotNull(result);
        assertEquals("config-1", result.getId());
        assertEquals("llm.api.key", result.getConfigKey());
    }

    @Test
    void testGetById_NotFound() {
        when(sysConfigRepository.selectById("config-999")).thenReturn(null);

        SysConfig result = sysConfigService.getById("config-999");

        assertNull(result);
    }

    @Test
    void testGetByKey_Found() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        SysConfig result = sysConfigService.getByKey("llm.api.key");

        assertNotNull(result);
        assertEquals("llm.api.key", result.getConfigKey());
    }

    @Test
    void testGetByKey_NotFound() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(null);

        SysConfig result = sysConfigService.getByKey("nonexistent");

        assertNull(result);
    }

    @Test
    void testGetValue_Found() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        String result = sysConfigService.getValue("llm.api.key");

        assertEquals("test-api-key", result);
    }

    @Test
    void testGetValue_NotFound() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(null);

        String result = sysConfigService.getValue("nonexistent");

        assertNull(result);
    }

    @Test
    void testGetAllConfigMap() {
        SysConfig config1 = new SysConfig();
        config1.setConfigKey("key1");
        config1.setConfigValue("value1");
        config1.setStatus("ACTIVE");

        SysConfig config2 = new SysConfig();
        config2.setConfigKey("key2");
        config2.setConfigValue("value2");
        config2.setStatus("ACTIVE");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(config1, config2));

        Map<String, String> result = sysConfigService.getAllConfigMap();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("value1", result.get("key1"));
        assertEquals("value2", result.get("key2"));
    }

    @Test
    void testCreate_Success() {
        SysConfig config = new SysConfig();
        config.setConfigKey("new.config");
        config.setConfigName("New Config");
        config.setConfigValue("new-value");

        when(sysConfigRepository.insert(any(SysConfig.class))).thenReturn(1);

        SysConfig result = sysConfigService.create(config);

        assertNotNull(result);
        assertNotNull(result.getId()); // UUID should be generated
        assertEquals("new.config", result.getConfigKey());
        assertEquals("STRING", result.getConfigType()); // default
        assertEquals("ACTIVE", result.getStatus()); // default
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        verify(sysConfigRepository, times(1)).insert(any(SysConfig.class));
    }

    @Test
    void testCreate_WithCustomTypeAndSystemFlag() {
        SysConfig config = new SysConfig();
        config.setConfigKey("new.config");
        config.setConfigName("New Config");
        config.setConfigValue("new-value");
        config.setConfigType("NUMBER");
        config.setIsSystem(true);

        when(sysConfigRepository.insert(any(SysConfig.class))).thenReturn(1);

        SysConfig result = sysConfigService.create(config);

        assertNotNull(result);
        assertEquals("NUMBER", result.getConfigType());
        assertTrue(result.getIsSystem());
    }

    @Test
    void testUpdate_Success() {
        when(sysConfigRepository.updateById(any(SysConfig.class))).thenReturn(1);

        testConfig.setConfigValue("updated-value");

        boolean result = sysConfigService.update(testConfig);

        assertTrue(result);
        assertNotNull(testConfig.getUpdatedAt());
        verify(sysConfigRepository, times(1)).updateById(testConfig);
    }

    @Test
    void testUpdateValue_Success() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);
        when(sysConfigRepository.updateById(any(SysConfig.class))).thenReturn(1);

        boolean result = sysConfigService.updateValue("llm.api.key", "new-value");

        assertTrue(result);
        assertEquals("new-value", testConfig.getConfigValue());
        assertNotNull(testConfig.getUpdatedAt());
    }

    @Test
    void testUpdateValue_NotFound() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(null);

        boolean result = sysConfigService.updateValue("nonexistent", "new-value");

        assertFalse(result);
        verify(sysConfigRepository, never()).updateById(any(SysConfig.class));
    }

    @Test
    void testDelete_Success() {
        when(sysConfigRepository.deleteById("config-1")).thenReturn(1);

        boolean result = sysConfigService.delete("config-1");

        assertTrue(result);
        verify(sysConfigRepository, times(1)).deleteById("config-1");
    }

    @Test
    void testDelete_Failed() {
        when(sysConfigRepository.deleteById("config-999")).thenReturn(0);

        boolean result = sysConfigService.delete("config-999");

        assertFalse(result);
    }

    @Test
    void testGetBoolean_TrueValue() {
        testConfig.setConfigValue("true");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        Boolean result = sysConfigService.getBoolean("feature.enabled");

        assertTrue(result);
    }

    @Test
    void testGetBoolean_OneValue() {
        testConfig.setConfigValue("1");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        Boolean result = sysConfigService.getBoolean("feature.enabled");

        assertTrue(result);
    }

    @Test
    void testGetBoolean_FalseValue() {
        testConfig.setConfigValue("false");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        Boolean result = sysConfigService.getBoolean("feature.enabled");

        assertFalse(result);
    }

    @Test
    void testGetBoolean_NotFound() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(null);

        Boolean result = sysConfigService.getBoolean("feature.enabled");

        assertNull(result);
    }

    @Test
    void testGetInt_ValidNumber() {
        testConfig.setConfigValue("100");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        Integer result = sysConfigService.getInt("port");

        assertEquals(100, result);
    }

    @Test
    void testGetInt_InvalidNumber() {
        testConfig.setConfigValue("not-a-number");

        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(testConfig);

        Integer result = sysConfigService.getInt("port");

        assertNull(result);
    }

    @Test
    void testGetInt_NotFound() {
        LambdaQueryChainWrapper<SysConfig> chain = new LambdaQueryChainWrapper<>(sysConfigRepository);
        when(sysConfigRepository.lambdaQuery()).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any())).thenReturn(chain);
        when(sysConfigRepository.lambdaQuery().eq(any(), any()).getEntity()).thenReturn(null);

        Integer result = sysConfigService.getInt("port");

        assertNull(result);
    }
}
