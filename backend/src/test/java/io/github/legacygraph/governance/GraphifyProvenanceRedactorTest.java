package io.github.legacygraph.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyProvenanceRedactor 单元测试。
 */
class GraphifyProvenanceRedactorTest {

    @TempDir
    Path tempDir;

    @Test
    void redactPathInsideProject() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        String filePath = tempDir.resolve("src/main/java/App.java").toString();
        String redacted = redactor.redactPath(filePath);

        assertEquals("src/main/java/App.java", redacted);
    }

    @Test
    void redactPathOutsideProject() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        String externalPath = "/Users/alice/.ssh/config";
        String redacted = redactor.redactPath(externalPath);

        assertEquals("[outside-project]", redacted);
    }

    @Test
    void redactNullPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(tempDir.toString());

        assertEquals("[unknown]", redactor.redactPath(null));
        assertEquals("[unknown]", redactor.redactPath(""));
        assertEquals("[unknown]", redactor.redactPath("   "));
    }

    @Test
    void isInsideProject() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        String insidePath = tempDir.resolve("src/main/java/App.java").toString();
        String outsidePath = "/tmp/external.txt";

        assertTrue(redactor.isInsideProject(insidePath));
        assertFalse(redactor.isInsideProject(outsidePath));
        assertFalse(redactor.isInsideProject(null));
        assertFalse(redactor.isInsideProject(""));
    }

    @Test
    void getProjectRoot() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        assertEquals(tempDir.toAbsolutePath().normalize().toString(), redactor.getProjectRoot());
    }

    @Test
    void normalizePathWithDotDot() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        // 创建子目录结构
        String subDir = tempDir.resolve("subdir").toString();
        String filePath = subDir + "/../src/App.java";
        
        String redacted = redactor.redactPath(filePath);
        assertEquals("src/App.java", redacted);
    }

    @Test
    void handlePathTraversalAttempt() {
        String projectRoot = tempDir.toString();
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(projectRoot);

        // 尝试通过 ../ 逃逸到项目外
        String escapePath = tempDir.toString() + "/../../../etc/passwd";
        String redacted = redactor.redactPath(escapePath);

        assertEquals("[outside-project]", redacted);
    }

    @Test
    void handleInvalidPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor(tempDir.toString());

        // 某些操作系统可能不支持的路径格式
        String invalidPath = "\0invalid";
        String redacted = redactor.redactPath(invalidPath);

        // 应该返回脱敏标记而不是抛出异常
        assertTrue(redacted.equals("[invalid-path]") || redacted.equals("[outside-project]"));
    }
}
