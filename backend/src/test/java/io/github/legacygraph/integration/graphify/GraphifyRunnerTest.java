package io.github.legacygraph.integration.graphify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyRunner 测试。
 */
class GraphifyRunnerTest {

    @Test
    void throwsWhenDisabled() {
        GraphifyProperties props = new GraphifyProperties();
        props.setEnabled(false);

        GraphifyCommandBuilder commandBuilder = new GraphifyCommandBuilder(props);
        GraphifyGraphParser parser = new GraphifyGraphParser();

        GraphifyRunner runner = new GraphifyRunner(props, commandBuilder, parser);

        assertThrows(GraphifyRunner.GraphifyRunException.class, () -> runner.run(Path.of("/tmp")));
    }

    @Test
    void isAvailableReturnsFalseWhenNotInstalled() {
        GraphifyProperties props = new GraphifyProperties();
        props.setExecutable("nonexistent-graphify-binary-xyz");

        GraphifyCommandBuilder commandBuilder = new GraphifyCommandBuilder(props);
        GraphifyGraphParser parser = new GraphifyGraphParser();

        GraphifyRunner runner = new GraphifyRunner(props, commandBuilder, parser);

        assertFalse(runner.isAvailable());
    }

    @Test
    void throwsWhenGraphJsonNotGenerated(@TempDir Path tempDir) {
        GraphifyProperties props = new GraphifyProperties();
        props.setEnabled(true);
        // 使用 echo 作为伪命令，不会生成 graph.json
        props.setExecutable("echo");
        props.setTimeoutSeconds(10);
        props.setWorkDirWhitelist(List.of(tempDir.toString()));

        GraphifyCommandBuilder commandBuilder = new GraphifyCommandBuilder(props);
        GraphifyGraphParser parser = new GraphifyGraphParser();

        GraphifyRunner runner = new GraphifyRunner(props, commandBuilder, parser);

        GraphifyRunner.GraphifyRunException ex = assertThrows(
                GraphifyRunner.GraphifyRunException.class,
                () -> runner.run(tempDir)
        );
        assertTrue(ex.getMessage().contains("graph.json"));
    }
}
