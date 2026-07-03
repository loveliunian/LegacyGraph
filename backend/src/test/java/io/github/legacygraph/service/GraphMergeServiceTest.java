package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.config.AgentConfigProperties;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import io.github.legacygraph.service.graph.GraphMergeService;

class GraphMergeServiceTest {

    private final AgentConfigProperties agentConfig = createDefaultConfig();

    private static AgentConfigProperties createDefaultConfig() {
        AgentConfigProperties config = new AgentConfigProperties();
        AgentConfigProperties.MergeConfig merge = config.getMerge();
        merge.setScoreNameWeight(0.35);
        merge.setScoreStructWeight(0.25);
        merge.setScoreEvidenceWeight(0.20);
        merge.setScoreRuntimeWeight(0.10);
        merge.setScoreHistoryWeight(0.10);
        merge.setAutoMergeThreshold(0.85);
        merge.setReviewThreshold(0.50);
        return config;
    }

    private GraphMergeService graphMergeService;

    @Test
    void testConstruction() {
        graphMergeService = new GraphMergeService(new FakeNeo4jGraphDao(), null, agentConfig);
        assertNotNull(graphMergeService);
    }

    @Test
    void executeMergeRecordsLineageAsValidJsonWhenNodeNameContainsReplacementTokens() throws Exception {
        FakeNeo4jGraphDao neo4jGraphDao = new FakeNeo4jGraphDao();
        graphMergeService = new GraphMergeService(neo4jGraphDao, null, agentConfig);
        GraphNode targetNode = new GraphNode();
        targetNode.setId("target-1");
        targetNode.setProjectId("project-1");
        targetNode.setVersionId("version-1");
        targetNode.setProperties("{\"owner\":\"team-a\"}");

        GraphNode mergeNode = new GraphNode();
        mergeNode.setId("merge-1");
        mergeNode.setProjectId("project-1");
        mergeNode.setVersionId("version-1");
        mergeNode.setNodeName("Order \"$1\" \\\\ path");
        mergeNode.setNodeType("SERVICE");

        neo4jGraphDao.mergeNode = mergeNode;
        neo4jGraphDao.targetNode = targetNode;

        graphMergeService.executeMerge("project-1", "target-1", "merge-1");

        JsonNode properties = new ObjectMapper().readTree(neo4jGraphDao.updatedNode.getProperties());
        assertEquals("team-a", properties.get("owner").asText());
        assertEquals("merge-1", properties.get("mergedFrom").get(0).get("id").asText());
        assertEquals("Order \"$1\" \\\\ path", properties.get("mergedFrom").get(0).get("name").asText());
    }

    private static class FakeNeo4jGraphDao extends Neo4jGraphDao {
        private GraphNode targetNode;
        private GraphNode mergeNode;
        private GraphNode updatedNode;

        private FakeNeo4jGraphDao() {
            super(null, null, null, null, null);
        }

        @Override
        public Optional<GraphNode> findNodeById(String nodeId) {
            if ("target-1".equals(nodeId)) {
                return Optional.ofNullable(targetNode);
            }
            if ("merge-1".equals(nodeId)) {
                return Optional.ofNullable(mergeNode);
            }
            return Optional.empty();
        }

        @Override
        public void updateEdgeFromNode(String oldNodeId, String newNodeId, String projectId) {
        }

        @Override
        public void updateEdgeToNode(String oldNodeId, String newNodeId, String projectId) {
        }

        @Override
        public void updateNode(GraphNode node) {
            this.updatedNode = node;
        }

        @Override
        public void deleteNode(String projectId, String versionId, String nodeId) {
        }
    }
}
