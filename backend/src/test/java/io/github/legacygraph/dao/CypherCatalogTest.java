package io.github.legacygraph.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CypherCatalogTest {

    @Test
    void safeIdentifierAcceptsSimpleLabelsAndRejectsCypherFragments() {
        assertEquals("OrderService", CypherCatalog.safeIdentifier("OrderService", "nodeType"));

        assertThrows(IllegalArgumentException.class,
                () -> CypherCatalog.safeIdentifier("Order`) DETACH DELETE n //", "nodeType"));
        assertThrows(IllegalArgumentException.class,
                () -> CypherCatalog.safeIdentifier("HAS-COLUMN", "edgeType"));
    }
}
