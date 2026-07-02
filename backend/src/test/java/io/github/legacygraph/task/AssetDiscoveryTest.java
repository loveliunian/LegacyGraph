package io.github.legacygraph.task;

import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AssetDiscovery 单元测试。
 * 验证资产发现（DB连接、子路径、文档）的逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AssetDiscoveryTest {

    @Mock
    private DbConnectionRepository dbConnectionRepository;

    @Mock
    private CodeRepoRepository codeRepoRepository;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private AssetDiscovery discovery;

    /**
     * 测试 null baseDir 时 discoverDbConnections 返回 0。
     */
    @Test
    void discoverDbConnections_nullBaseDir_returnsZero() {
        int result = discovery.discoverDbConnections("project-1", null);

        assertEquals(0, result);
    }

    /**
     * 测试 null baseDir 时 discoverSubPaths 返回 0。
     */
    @Test
    void discoverSubPaths_nullBaseDir_returnsZero() {
        int result = discovery.discoverSubPaths("project-1", null);

        assertEquals(0, result);
    }

    /**
     * 测试 null baseDir 时 discoverDocuments 返回 0。
     */
    @Test
    void discoverDocuments_nullBaseDir_returnsZero() {
        int result = discovery.discoverDocuments("project-1", "v1", null);

        assertEquals(0, result);
    }

    /**
     * 测试不存在的目录 discoverDocuments 返回 0。
     */
    @Test
    void discoverDocuments_nonExistentDir_returnsZero() {
        int result = discovery.discoverDocuments(
                "project-1", "v1", "/non/existent/path");

        assertEquals(0, result);
    }

    /**
     * 测试正常 discoverSubPaths 调用不抛异常。
     */
    @Test
    void discoverSubPaths_withExistingDir_doesNotThrow() {
        assertDoesNotThrow(() -> discovery.discoverSubPaths(
                "project-1", System.getProperty("java.io.tmpdir")));
    }
}
