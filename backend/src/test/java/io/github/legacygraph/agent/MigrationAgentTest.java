package io.github.legacygraph.agent;

import io.github.legacygraph.dto.MigrationConversion;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MigrationAgent 单元测试。
 * 验证迁移转换委托 LLM 及参数处理。
 */
@ExtendWith(MockitoExtension.class)
class MigrationAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private MigrationAgent agent;

    /**
     * 测试正常迁移转换委托给 LLM。
     */
    @Test
    void convert_delegatesToLlmGateway() {
        MigrationConversion expected = new MigrationConversion();
        expected.setMigratedCode("public class NewConfig {}");
        expected.setSummary("Spring Boot 2 to 3 migration");
        when(llmGateway.callWithTemplate(eq("project-1"), eq("migration-convert"),
                anyMap(), eq(MigrationConversion.class)))
                .thenReturn(expected);

        MigrationConversion result = agent.convert(
                "project-1", "SpringBoot2_to_3",
                "/src/main/java/com/demo/OldConfig.java",
                "public class OldConfig {}",
                "禁用默认登录页");

        assertNotNull(result);
        assertEquals("Spring Boot 2 to 3 migration", result.getSummary());
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("migration-convert"),
                anyMap(), eq(MigrationConversion.class));
    }

    /**
     * 测试空参数时的默认值处理。
     */
    @Test
    void convertWithNullParams_usesDefaults() {
        when(llmGateway.callWithTemplate(anyString(), anyString(), anyMap(),
                eq(MigrationConversion.class)))
                .thenReturn(new MigrationConversion());

        MigrationConversion result = agent.convert("project-1", null, null, null, null);

        assertNotNull(result);
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("migration-convert"),
                anyMap(), eq(MigrationConversion.class));
    }

    /**
     * 测试 LLM 返回 null 时的处理。
     */
    @Test
    void convertWhenLlmReturnsNull_returnsNull() {
        when(llmGateway.callWithTemplate(anyString(), anyString(), anyMap(),
                eq(MigrationConversion.class)))
                .thenReturn(null);

        MigrationConversion result = agent.convert(
                "project-1", "SpringBoot2_to_3",
                "/src/main/java/Config.java", "code", null);

        assertNull(result);
    }
}
