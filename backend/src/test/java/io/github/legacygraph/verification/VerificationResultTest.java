package io.github.legacygraph.verification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VerificationResult} 数据模型测试。
 */
class VerificationResultTest {

    @Test
    void builder_setsAllFields() {
        VerifiedEdge confirmedEdge = VerifiedEdge.builder()
                .fromNodeKey("com.example.UserService")
                .toNodeKey("com.example.UserMapper")
                .edgeType("CALLS")
                .confidence(0.9)
                .sourceTool("mcp")
                .build();

        VerifiedNodeProperty prop = VerifiedNodeProperty.builder()
                .nodeKey("com.example.UserService")
                .propertyName("complexity")
                .propertyValue(12)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp-verification")
                .confirmedEdges(List.of(confirmedEdge))
                .missingEdges(List.of())
                .suspiciousEdges(List.of())
                .nodeProperties(List.of(prop))
                .totalChecked(5)
                .totalConfirmed(1)
                .build();

        assertEquals("mcp-verification", result.getAdapterName());
        assertEquals(1, result.getConfirmedEdges().size());
        assertTrue(result.getMissingEdges().isEmpty());
        assertTrue(result.getSuspiciousEdges().isEmpty());
        assertEquals(1, result.getNodeProperties().size());
        assertEquals(5, result.getTotalChecked());
        assertEquals(1, result.getTotalConfirmed());
    }

    @Test
    void empty_createsZeroResult() {
        VerificationResult empty = VerificationResult.empty("mcp-verification");

        assertEquals("mcp-verification", empty.getAdapterName());
        assertTrue(empty.getConfirmedEdges().isEmpty());
        assertTrue(empty.getMissingEdges().isEmpty());
        assertTrue(empty.getSuspiciousEdges().isEmpty());
        assertTrue(empty.getNodeProperties().isEmpty());
        assertEquals(0, empty.getTotalChecked());
        assertEquals(0, empty.getTotalConfirmed());
    }

    @Test
    void defaultLists_areMutable() {
        VerificationResult result = VerificationResult.builder()
                .adapterName("test")
                .build();

        result.getConfirmedEdges().add(VerifiedEdge.builder().build());
        result.getMissingEdges().add(VerifiedEdge.builder().build());

        assertEquals(1, result.getConfirmedEdges().size());
        assertEquals(1, result.getMissingEdges().size());
    }

    @Test
    void verifiedEdge_fieldsAccessible() {
        VerifiedEdge edge = VerifiedEdge.builder()
                .fromNodeKey("A")
                .toNodeKey("B")
                .edgeType("EXTENDS")
                .confidence(0.85)
                .sourceTool("joern")
                .build();

        assertEquals("A", edge.getFromNodeKey());
        assertEquals("B", edge.getToNodeKey());
        assertEquals("EXTENDS", edge.getEdgeType());
        assertEquals(0.85, edge.getConfidence());
        assertEquals("joern", edge.getSourceTool());
    }

    @Test
    void verifiedNodeProperty_fieldsAccessible() {
        VerifiedNodeProperty prop = VerifiedNodeProperty.builder()
                .nodeKey("com.example.Service")
                .propertyName("complexity")
                .propertyValue(42)
                .sourceTool("sonar")
                .build();

        assertEquals("com.example.Service", prop.getNodeKey());
        assertEquals("complexity", prop.getPropertyName());
        assertEquals(42, prop.getPropertyValue());
        assertEquals("sonar", prop.getSourceTool());
    }
}
