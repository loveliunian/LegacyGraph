package io.github.legacygraph.integration.graphify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyProperties 测试。
 */
class GraphifyPropertiesTest {

    @Test
    void testDefaultValues() {
        GraphifyProperties props = new GraphifyProperties();

        assertTrue(props.isEnabled());
        assertEquals("graphify", props.getExecutable());
        assertEquals(300, props.getTimeoutSeconds());
        assertEquals(500_000, props.getMaxOutputBytes());
        assertEquals("graphify-out", props.getOutputDirName());
        assertFalse(props.isKeepTempFiles());
        assertNotNull(props.getWorkDirWhitelist());
        assertTrue(props.getWorkDirWhitelist().isEmpty());
        assertNotNull(props.getExtraArgs());
        assertTrue(props.getExtraArgs().isEmpty());
    }

    @Test
    void testCustomValues() {
        GraphifyProperties props = new GraphifyProperties();

        props.setEnabled(false);
        props.setExecutable("custom-graphify");
        props.setTimeoutSeconds(600);
        props.setMaxOutputBytes(1_000_000);
        props.setOutputDirName("custom-output");
        props.setKeepTempFiles(true);

        assertFalse(props.isEnabled());
        assertEquals("custom-graphify", props.getExecutable());
        assertEquals(600, props.getTimeoutSeconds());
        assertEquals(1_000_000, props.getMaxOutputBytes());
        assertEquals("custom-output", props.getOutputDirName());
        assertTrue(props.isKeepTempFiles());
    }
}
