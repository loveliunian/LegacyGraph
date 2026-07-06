package io.github.legacygraph.query;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.governance.GraphifyAccessPolicy;
import io.github.legacygraph.governance.GraphifyProvenanceRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphifyQuestionServiceTest {

    private GraphifyQuestionService service;
    private GraphifyAccessPolicy accessPolicy;
    private GraphifyProvenanceRedactor provenanceRedactor;
    private Neo4jGraphDao graphDao;

    @BeforeEach
    void setUp() {
        accessPolicy = new GraphifyAccessPolicy();
        provenanceRedactor = new GraphifyProvenanceRedactor("/test/project");
        graphDao = mock(Neo4jGraphDao.class);
        service = new GraphifyQuestionService(accessPolicy, provenanceRedactor, graphDao);
    }

    @Test
    void emptyQuestionReturnsZeroConfidence() {
        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", " ", List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_VIEWER"))
        );
        
        assertThat(answer.answer()).isEqualTo("Question is empty.");
        assertThat(answer.confidence()).isZero();
        assertThat(answer.evidenceIds()).isEmpty();
    }

    @Test
    void nullQuestionReturnsZeroConfidence() {
        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", null, List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_VIEWER"))
        );
        
        assertThat(answer.answer()).isEqualTo("Question is empty.");
        assertThat(answer.confidence()).isZero();
    }

    @Test
    void validQuestionReturnsAnswer() {
        mockGraphEvidence(evidenceNode("node-1", "service:PaymentService", "PaymentService",
            "/test/project/src/main/java/PaymentService.java"));

        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", "PaymentService impact", List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_VIEWER"))
        );
        
        assertThat(answer.answer()).isNotBlank();
        assertThat(answer.confidence()).isGreaterThan(0.0);
        assertThat(answer.evidenceIds()).containsExactly("node-1");
        assertThat(answer.evidenceIds()).noneMatch(id -> id.startsWith("evidence-"));
    }

    @Test
    void maxEvidenceLimitsResults() {
        mockGraphEvidence(
            evidenceNode("node-1", "service:ServiceA", "ServiceA", "/test/project/src/main/java/ServiceA.java"),
            evidenceNode("node-2", "service:ServiceB", "ServiceB", "/test/project/src/main/java/ServiceB.java"),
            evidenceNode("node-3", "service:ServiceC", "ServiceC", "/test/project/src/main/java/ServiceC.java"),
            evidenceNode("node-4", "service:ServiceD", "ServiceD", "/test/project/src/main/java/ServiceD.java")
        );

        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", "Service impact", List.of("GRAPHIFY_AST"), 3, Set.of("GRAPH_VIEWER"))
        );
        
        assertThat(answer.evidenceIds()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void maxEvidenceValidation() {
        assertThatThrownBy(() -> 
            new GraphifyQuestionRequest("p1", "q", List.of("GRAPHIFY_AST"), 0, Set.of("GRAPH_VIEWER"))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxEvidence");

        assertThatThrownBy(() -> 
            new GraphifyQuestionRequest("p1", "q", List.of("GRAPHIFY_AST"), 25, Set.of("GRAPH_VIEWER"))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxEvidence");
    }

    @Test
    void projectIdValidation() {
        assertThatThrownBy(() -> 
            new GraphifyQuestionRequest("", "q", List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_VIEWER"))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("projectId");
    }

    @Test
    void accessDeniedWithoutViewerRole() {
        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", "What is the impact?", List.of("GRAPHIFY_AST"), 5, Set.of())
        );
        
        assertThat(answer.answer()).contains("Access denied");
        assertThat(answer.confidence()).isZero();
    }

    @Test
    void evidenceViewerSeesRawPaths() {
        mockGraphEvidence(evidenceNode("node-1", "service:PaymentService", "PaymentService",
            "/test/project/src/main/java/PaymentService.java"));

        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", "PaymentService paths", List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_EVIDENCE_VIEWER"))
        );
        
        // GRAPH_EVIDENCE_VIEWER 应该能看到原始路径（不在项目外的情况下）
        assertThat(answer.sourcePaths()).isNotEmpty();
        assertThat(answer.sourcePaths()).contains("/test/project/src/main/java/PaymentService.java");
    }

    @Test
    void viewerSeesRedactedPaths() {
        mockGraphEvidence(evidenceNode("node-1", "service:PaymentService", "PaymentService",
            "/test/project/src/main/java/PaymentService.java"));

        GraphifyQuestionAnswer answer = service.answer(
            new GraphifyQuestionRequest("p1", "PaymentService paths", List.of("GRAPHIFY_AST"), 5, Set.of("GRAPH_VIEWER"))
        );
        
        // GRAPH_VIEWER 应该看到脱敏后的路径
        assertThat(answer.sourcePaths()).isNotEmpty();
        assertThat(answer.sourcePaths()).contains("src/main/java/PaymentService.java");
    }

    @Test
    void answerConfidenceValidation() {
        assertThatThrownBy(() -> 
            new GraphifyQuestionAnswer("answer", Set.of(), List.of(), 1.5, List.of())
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("confidence");
    }

    @Test
    void emptyQuestionFactory() {
        GraphifyQuestionAnswer answer = GraphifyQuestionAnswer.emptyQuestion();
        
        assertThat(answer.answer()).isEqualTo("Question is empty.");
        assertThat(answer.confidence()).isZero();
        assertThat(answer.evidenceIds()).isEmpty();
        assertThat(answer.sourcePaths()).isEmpty();
    }

    @Test
    void noEvidenceFoundFactory() {
        GraphifyQuestionAnswer answer = GraphifyQuestionAnswer.noEvidenceFound("no matching nodes");
        
        assertThat(answer.answer()).contains("Unable to find sufficient evidence");
        assertThat(answer.confidence()).isEqualTo(0.2);
        assertThat(answer.warnings()).isNotEmpty();
    }

    private void mockGraphEvidence(GraphNode... nodes) {
        when(graphDao.queryNodes(
            eq("p1"),
            isNull(),
            isNull(),
            isNull(),
            eq("GRAPHIFY_AST"),
            isNull(),
            isNull(),
            anyInt()
        )).thenReturn(List.of(nodes));
    }

    private GraphNode evidenceNode(String id, String nodeKey, String nodeName, String sourcePath) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setProjectId("p1");
        node.setVersionId("v1");
        node.setNodeType("Service");
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(nodeName);
        node.setSourceType("GRAPHIFY_AST");
        node.setSourcePath(sourcePath);
        node.setConfidence(BigDecimal.valueOf(0.8));
        return node;
    }
}
