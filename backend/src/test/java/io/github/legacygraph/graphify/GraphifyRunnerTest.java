package io.github.legacygraph.graphify;

import io.github.legacygraph.integration.graphify.GraphifyProperties;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GraphifyRunnerTest {

    @Test
    void disabledGraphifyIsNotAvailableWithoutTouchingCli() {
        GraphifyProperties properties = new GraphifyProperties();
        properties.setEnabled(false);

        GraphifyRunner runner = new GraphifyRunner(properties, null, null);

        assertFalse(runner.isAvailable());
    }
}
