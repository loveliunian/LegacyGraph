package io.github.legacygraph.integration.graphify;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyCommandBuilder 测试。
 */
class GraphifyCommandBuilderTest {

    @Test
    void testBuildAnalyzeCommand() {
        GraphifyProperties props = new GraphifyProperties();
        props.setExecutable("graphify");
        props.setExtraArgs(List.of("--verbose"));

        GraphifyCommandBuilder builder = new GraphifyCommandBuilder(props);

        Path sourceDir = Path.of("/tmp/source");
        Path outputDir = Path.of("/tmp/output");

        List<String> command = builder.buildAnalyzeCommand(sourceDir, outputDir);

        assertNotNull(command);
        assertEquals(7, command.size()); // executable + analyze + --source + path + --output + path + --verbose
        assertEquals("graphify", command.get(0));
        assertEquals("analyze", command.get(1));
        assertEquals("--source", command.get(2));
        assertTrue(command.get(3).contains("/tmp/source"));
        assertEquals("--output", command.get(4));
        assertTrue(command.get(5).contains("/tmp/output"));
        assertEquals("--verbose", command.get(6));
    }

    @Test
    void testBuildAnalyzeCommandWithExtraArgs() {
        GraphifyProperties props = new GraphifyProperties();
        props.setExecutable("graphify");
        props.setExtraArgs(List.of("--verbose", "--debug"));

        GraphifyCommandBuilder builder = new GraphifyCommandBuilder(props);

        Path sourceDir = Path.of("/tmp/source");
        Path outputDir = Path.of("/tmp/output");

        List<String> command = builder.buildAnalyzeCommand(sourceDir, outputDir);

        assertNotNull(command);
        assertEquals(8, command.size()); // 6 base + 2 extra
        assertEquals("--verbose", command.get(6));
        assertEquals("--debug", command.get(7));
    }

    @Test
    void testBuildVersionCommand() {
        GraphifyProperties props = new GraphifyProperties();
        props.setExecutable("graphify");

        GraphifyCommandBuilder builder = new GraphifyCommandBuilder(props);

        List<String> command = builder.buildVersionCommand();

        assertNotNull(command);
        assertEquals(2, command.size());
        assertEquals("graphify", command.get(0));
        assertEquals("--version", command.get(1));
    }

    @Test
    void testBuildHelpCommand() {
        GraphifyProperties props = new GraphifyProperties();
        props.setExecutable("graphify");

        GraphifyCommandBuilder builder = new GraphifyCommandBuilder(props);

        List<String> command = builder.buildHelpCommand();

        assertNotNull(command);
        assertEquals(2, command.size());
        assertEquals("graphify", command.get(0));
        assertEquals("--help", command.get(1));
    }
}
