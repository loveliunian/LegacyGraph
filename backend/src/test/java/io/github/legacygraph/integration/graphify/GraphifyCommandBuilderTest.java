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
        props.setExtraArgs(List.of("--no-viz", "--timing"));

        GraphifyCommandBuilder builder = new GraphifyCommandBuilder(props);

        Path sourceDir = Path.of("/tmp/source");
        Path outputDir = Path.of("/tmp/output");

        List<String> command = builder.buildAnalyzeCommand(sourceDir, outputDir);

        assertNotNull(command);
        assertEquals(5, command.size()); // executable + extract + source path + extra args
        assertEquals("graphify", command.get(0));
        assertEquals("extract", command.get(1));
        assertTrue(command.get(2).contains("/tmp/source"));
        assertEquals("--no-viz", command.get(3));
        assertEquals("--timing", command.get(4));
        assertFalse(command.contains("analyze"));
        assertFalse(command.contains("--source"));
        assertFalse(command.contains("--output"));
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
        assertEquals(5, command.size()); // executable + extract + source path + 2 extra
        assertEquals("--verbose", command.get(3));
        assertEquals("--debug", command.get(4));
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
