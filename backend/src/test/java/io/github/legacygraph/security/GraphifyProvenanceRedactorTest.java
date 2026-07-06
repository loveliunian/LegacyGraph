package io.github.legacygraph.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GraphifyProvenanceRedactor}.
 */
class GraphifyProvenanceRedactorTest {

    @Test
    @DisplayName("项目内路径转为相对路径")
    void shouldRelativizeInternalPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor("/home/user/project");

        String result = redactor.redactPath("/home/user/project/src/main/java/Service.java");

        assertThat(result).isEqualTo("src/main/java/Service.java");
    }

    @Test
    @DisplayName("项目外路径返回 [outside-project]")
    void shouldMaskExternalPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor("/home/user/project");

        String result = redactor.redactPath("/home/user/other-project/Config.java");

        assertThat(result).isEqualTo("[outside-project]");
    }

    @Test
    @DisplayName("空路径返回 [unknown]")
    void shouldHandleEmptyPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor("/home/user/project");

        assertThat(redactor.redactPath("")).isEqualTo("[unknown]");
        assertThat(redactor.redactPath(null)).isEqualTo("[unknown]");
        assertThat(redactor.redactPath("   ")).isEqualTo("[unknown]");
    }

    @Test
    @DisplayName("项目根目录本身返回空字符串")
    void shouldHandleProjectRoot() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor("/home/user/project");

        String result = redactor.redactPath("/home/user/project");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("深层嵌套路径正确转为相对路径")
    void shouldHandleDeeplyNestedPath() {
        GraphifyProvenanceRedactor redactor = new GraphifyProvenanceRedactor("/home/user/project");

        String result = redactor.redactPath("/home/user/project/a/b/c/d/e/File.java");

        assertThat(result).isEqualTo("a/b/c/d/e/File.java");
    }
}
