package io.github.legacygraph.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import io.github.legacygraph.service.system.PromptTemplateService;

/**
 * PromptTemplateLoader 单元测试
 * <p>
 * 测试模板加载、变量替换、模板不存在等场景。
 * 测试模板文件位于 src/test/resources/prompts/ 目录下，
 * 运行时自动在 classpath 中。
 */
class PromptTemplateLoaderTest {

    private PromptTemplateLoader loader;

    @BeforeEach
    void setUp() {
        // DB 中无模板时回退到 classpath 文件（测试用 src/test/resources/prompts 下的模板）
        io.github.legacygraph.service.system.PromptTemplateService svc =
                org.mockito.Mockito.mock(io.github.legacygraph.service.system.PromptTemplateService.class);
        org.mockito.Mockito.lenient().when(svc.getActiveByCode(
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        loader = new PromptTemplateLoader(svc);
    }

    /**
     * 用例1: 加载模板 — 加载一个存在的模板文件，验证内容正确加载
     */
    @Test
    void testLoadTemplate() {
        // hello_prompt.txt 内容: "Hello, {name}! Welcome to {project}.\n"
        String template = loader.render("hello_prompt", Map.of());
        assertNotNull(template);
        assertTrue(template.startsWith("Hello, "));
        assertTrue(template.contains("{name}"));
        assertTrue(template.contains("{project}"));
    }

    /**
     * 用例2: render变量替换 — 加载模板并替换所有变量，验证渲染结果正确
     */
    @Test
    void testRenderWithVariableSubstitution() {
        String result = loader.render("hello_prompt", Map.of(
                "name", "Alice",
                "project", "LegacyGraph"
        ));
        assertEquals("Hello, Alice! Welcome to LegacyGraph.\n", result);
    }

    /**
     * 用例3: 模板不存在 — 当加载不存在的模板时，应抛出 IllegalArgumentException
     * <p>
     * 注：当前实现中 loadTemplate() 在模板不存在时抛出 IllegalArgumentException，
     * 而不是返回默认值。如需返回默认值，需修改 PromptTemplateLoader 实现。
     */
    @Test
    void testTemplateNotFoundThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> loader.render("non_existent_template", Map.of()));
    }

    /**
     * 测试 render 替换部分变量（部分已替换，部分保持变量占位符）
     */
    @Test
    void testRenderWithPartialVariables() {
        String result = loader.render("hello_prompt", Map.of("name", "Bob"));
        assertEquals("Hello, Bob! Welcome to {project}.\n", result);
    }

    /**
     * 测试 clearCache 清空缓存后重新加载
     */
    @Test
    void testClearCacheReloadsTemplate() {
        // 第一次加载
        String first = loader.render("hello_prompt", Map.of());
        assertNotNull(first);

        // 清空缓存
        loader.clearCache();

        // 重新加载应正常
        String second = loader.render("hello_prompt", Map.of());
        assertNotNull(second);
        assertEquals(first, second);
    }

    /**
     * 测试多个模板各自独立加载
     */
    @Test
    void testMultipleTemplates() {
        String hello = loader.render("hello_prompt", Map.of(
                "name", "Charlie",
                "project", "Test"
        ));
        assertEquals("Hello, Charlie! Welcome to Test.\n", hello);

        String review = loader.render("code_review", Map.of(
                "language", "Java",
                "code", "public class Hello {}"
        ));
        assertNotNull(review);
        assertTrue(review.contains("Java"));
        assertTrue(review.contains("public class Hello {}"));
    }
}
