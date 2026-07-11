package io.github.legacygraph.extractors;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.adapter.JavaCodeAdapter;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Task 9 单元测试：验证文件 I/O 缓存机制。
 *
 * <p>验证两点：
 * <ol>
 *   <li>缓存非空时，extractor/adapter 优先用缓存内容（不读文件）——
 *       通过「文件路径不存在 + 非空缓存 → 成功解析」间接证明未读文件，否则会抛 NoSuchFileException。</li>
 *   <li>缓存为 null 时，fallback 读文件正常。</li>
 * </ol>
 * </p>
 */
class AssetContentCacheTest {

    @TempDir
    Path tempDir;

    // ===== JavaStructureExtractor =====

    /**
     * 缓存非空时，即使文件路径不存在也能成功解析 → 证明用的是缓存内容而非读文件。
     */
    @Test
    void structureExtractor_usesCachedContent_withoutReadingFile() throws Exception {
        Path nonExistent = tempDir.resolve("does-not-exist.java");
        String cached = "package demo;\npublic class Foo { void bar() {} }";

        var classes = new JavaStructureExtractor().extractFromFile(nonExistent, cached);

        assertEquals(1, classes.size(), "缓存内容应被解析出 1 个类");
        assertEquals("Foo", classes.get(0).getClassName());
    }

    /**
     * 缓存为 null（走单参重载）→ fallback 读真实文件正常。
     */
    @Test
    void structureExtractor_fallbackToReadFileWhenCacheNull() throws Exception {
        Path file = tempDir.resolve("Real.java");
        Files.writeString(file, "package demo;\npublic class Real { void baz() {} }");

        var classes = new JavaStructureExtractor().extractFromFile(file);

        assertEquals(1, classes.size());
        assertEquals("Real", classes.get(0).getClassName());
    }

    // ===== ServiceCallExtractor =====

    /**
     * 缓存非空时，即使文件不存在也能解析（不抛 NoSuchFileException）→ 证明用缓存。
     */
    @Test
    void serviceCallExtractor_usesCachedContent_withoutReadingFile() throws Exception {
        File nonExistent = tempDir.resolve("no-such.java").toFile();
        String cached = "package demo;\npublic class Ctrl { void m() {} }";

        var rels = new ServiceCallExtractor().extractFromFile(nonExistent, cached);

        assertNotNull(rels, "用缓存解析应返回非 null 列表（无调用关系则为空列表）");
    }

    /**
     * 缓存为 null → fallback 读真实文件正常。
     */
    @Test
    void serviceCallExtractor_fallbackToReadFileWhenCacheNull() throws Exception {
        Path file = tempDir.resolve("Svc.java");
        Files.writeString(file, "package demo;\npublic class Svc { void m() {} }");

        var rels = new ServiceCallExtractor().extractFromFile(file.toFile());

        assertNotNull(rels);
    }

    // ===== JavaCodeAdapter.supports() =====

    /**
     * supports() 优先用 cachedContent 判断注解，file 为 null 也能判断。
     */
    @Test
    void javaCodeAdapter_supports_usesCachedContent() {
        JavaCodeAdapter adapter = newAdapter();
        ScanContext ctx = ScanContext.builder().build();

        SourceAsset withController = SourceAsset.builder()
                .fileType("java")
                .cachedContent("@RestController\npublic class C {}")
                .build();
        assertTrue(adapter.supports(ctx, withController), "缓存含 @RestController 应返回 true");

        SourceAsset withoutController = SourceAsset.builder()
                .fileType("java")
                .cachedContent("class Plain {}")
                .build();
        assertFalse(adapter.supports(ctx, withoutController), "缓存不含 Controller 注解应返回 false");
    }

    /**
     * cachedContent 为 null → fallback 读文件扫描注解。
     */
    @Test
    void javaCodeAdapter_supports_fallbackToReadFileWhenCacheNull() throws Exception {
        Path file = tempDir.resolve("Ctrl.java");
        Files.writeString(file, "@RestController\npublic class Ctrl {}");
        JavaCodeAdapter adapter = newAdapter();
        ScanContext ctx = ScanContext.builder().build();

        SourceAsset asset = SourceAsset.builder()
                .fileType("java")
                .file(file)
                .build();
        assertTrue(adapter.supports(ctx, asset), "fallback 读文件应识别 @RestController");
    }

    private JavaCodeAdapter newAdapter() {
        return new JavaCodeAdapter(
                mock(GraphBuilder.class),
                mock(JavaStructureExtractor.class),
                mock(PackageExtractor.class),
                mock(io.github.legacygraph.extractors.adapter.FactPersister.class));
    }
}
