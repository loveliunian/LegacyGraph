package io.github.legacygraph.dao;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.types.Relationship;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class Neo4jGraphDaoStructureTest {

    @Test
    void edgeMappingShouldRequireEndpointNodes() {
        boolean hasRelationshipOnlyMapper = Arrays.stream(Neo4jGraphDao.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("recordToEdge")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].equals(Relationship.class));

        assertFalse(hasRelationshipOnlyMapper,
                "GraphEdge mapping must receive endpoint nodes so fromNodeId/toNodeId use application ids");
    }
}
