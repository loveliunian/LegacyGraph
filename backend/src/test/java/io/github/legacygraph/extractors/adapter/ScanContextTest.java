package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScanContext 单元测试。
 * 验证扫描上下文对象的构建与字段访问。
 */
@ExtendWith(MockitoExtension.class)
class ScanContextTest {

    /**
     * 测试 Builder 构建完整上下文。
     */
    @Test
    void builder_createsContextWithAllFields() {
        ScanContext ctx = ScanContext.builder()
                .projectId("project-1")
                .versionId("v1")
                .baseDir("/workspace")
                .backendDir("src/main/java")
                .frontendDir("src/main/frontend")
                .config(Map.of("timeout", 300))
                .build();

        assertEquals("project-1", ctx.getProjectId());
        assertEquals("v1", ctx.getVersionId());
        assertEquals("/workspace", ctx.getBaseDir());
        assertEquals("src/main/java", ctx.getBackendDir());
        assertEquals("src/main/frontend", ctx.getFrontendDir());
        assertEquals(Map.of("timeout", 300), ctx.getConfig());
    }

    /**
     * 测试默认构造函数。
     */
    @Test
    void defaultConstructor_createsEmptyContext() {
        ScanContext ctx = new ScanContext();

        assertNull(ctx.getProjectId());
        assertNull(ctx.getVersionId());
        assertNull(ctx.getBaseDir());
        assertNull(ctx.getConfig());
    }

    /**
     * 测试全参数构造函数。
     */
    @Test
    void allArgsConstructor_createsValidContext() {
        Map<String, Object> config = Map.of("key", "value");
        ScanContext ctx = new ScanContext(
                "project-1", "v1", "/workspace",
                "backend", "frontend", config);

        assertEquals("project-1", ctx.getProjectId());
        assertEquals("v1", ctx.getVersionId());
        assertEquals("/workspace", ctx.getBaseDir());
        assertEquals("backend", ctx.getBackendDir());
        assertEquals("frontend", ctx.getFrontendDir());
        assertEquals(config, ctx.getConfig());
    }
}
