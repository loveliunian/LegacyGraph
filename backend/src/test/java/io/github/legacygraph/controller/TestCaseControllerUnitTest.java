package io.github.legacygraph.controller;

import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCaseControllerUnitTest {

    @Test
    void parseApiEndpoint_UsesMethodAndPathFromApiNodeKey() {
        GraphNode node = new GraphNode();
        node.setNodeType("ApiEndpoint");
        node.setNodeKey("POST /lg/orders/{orderId}/cancel");

        assertEquals("POST", TestCaseController.extractHttpMethod(node));
        assertEquals("/lg/orders/{orderId}/cancel", TestCaseController.extractApiEndpoint(node));
    }
}
