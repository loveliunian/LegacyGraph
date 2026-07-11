package io.github.legacygraph.service.qa;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.qa.AccessContext;
import io.github.legacygraph.dto.qa.VerificationResult;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * EvidenceVerifier 单元测试 — 验证证据存在性/归属/ACL/sourceLocation/答案声明匹配。
 */
@ExtendWith(MockitoExtension.class)
class EvidenceVerifierTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private GraphReleaseRepository graphReleaseRepository;

    private EvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new EvidenceVerifier(neo4jGraphDao, graphReleaseRepository);
    }

    private EvidenceItem graphEvidence(String ref, String title, String sourceFile) {
        EvidenceItem ev = new EvidenceItem();
        ev.setSourceKind("GRAPH_NODE");
        ev.setRef(ref);
        ev.setTitle(title);
        ev.setSourceFile(sourceFile);
        ev.setExcerpt("test excerpt");
        return ev;
    }

    private EvidenceItem docEvidence(String ref, String title, String sourceFile) {
        EvidenceItem ev = new EvidenceItem();
        ev.setSourceKind("DOC_CHUNK");
        ev.setRef(ref);
        ev.setTitle(title);
        ev.setSourceFile(sourceFile);
        ev.setExcerpt("doc excerpt");
        return ev;
    }

    @Test
    void verify_emptyAnswer_returnsFalse() {
        VerificationResult result = verifier.verify("", List.of(), "p1", null, AccessContext.PUBLIC);
        assertFalse(result.verified());
        assertEquals(0.0, result.evidenceCoverage());
        assertTrue(result.violations().contains("答案为空"));
    }

    @Test
    void verify_noEvidences_returnsFalse() {
        VerificationResult result = verifier.verify("some answer", List.of(), "p1", null, AccessContext.PUBLIC);
        assertFalse(result.verified());
        assertTrue(result.violations().contains("无证据"));
    }

    @Test
    void verify_allEvidencesValid_highCoverage() {
        EvidenceItem ev1 = graphEvidence("node-1", "OrderService", "src/OrderService.java");
        EvidenceItem ev2 = graphEvidence("node-2", "OrderController", "src/OrderController.java");

        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setProjectId("p1");
        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setProjectId("p1");

        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(node1, node2));

        String answer = "OrderService 处理订单逻辑，OrderController 暴露 REST API。";
        VerificationResult result = verifier.verify(answer, List.of(ev1, ev2), "p1", null, AccessContext.PUBLIC);

        assertTrue(result.verified());
        assertTrue(result.evidenceCoverage() >= 0.6);
        assertTrue(result.evidenceReliability() > 0.5);
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void verify_evidenceNotInGraph_returnsViolation() {
        EvidenceItem ev = graphEvidence("missing-node", "SomeClass", "src/SomeClass.java");
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of());

        VerificationResult result = verifier.verify("SomeClass does something", List.of(ev), "p1", null, AccessContext.PUBLIC);

        assertFalse(result.verified());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("证据不存在")));
    }

    @Test
    void verify_evidenceFromWrongProject_returnsViolation() {
        EvidenceItem ev = graphEvidence("node-1", "SomeClass", "src/SomeClass.java");
        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setProjectId("other-project");
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(node));

        VerificationResult result = verifier.verify("SomeClass", List.of(ev), "p1", null, AccessContext.PUBLIC);

        assertFalse(result.verified());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("证据归属不符")));
    }

    @Test
    void verify_lowCoverage_marksLowConfidence() {
        // 答案引用了很多证据中没有的标识符
        EvidenceItem ev = graphEvidence("node-1", "OrderService", "src/OrderService.java");
        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setProjectId("p1");
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(node));

        String answer = "OrderService 处理订单。另外 FooBar 和 BazQux 以及 QuxWibble 也参与了这个流程。";
        VerificationResult result = verifier.verify(answer, List.of(ev), "p1", null, AccessContext.PUBLIC);

        assertTrue(result.isLowCoverage());
        assertFalse(result.verified());
    }

    @Test
    void verify_docEvidenceNotCheckedInGraph() {
        // DOC_CHUNK 类型证据不查图谱，直接通过存在性校验
        EvidenceItem ev = docEvidence("doc-1", "README.md", "docs/README.md");
        // 不需要 mock neo4jGraphDao

        VerificationResult result = verifier.verify("README.md", List.of(ev), "p1", null, AccessContext.PUBLIC);

        assertTrue(result.violations().isEmpty());
        assertTrue(result.evidenceReliability() > 0);
    }

    @Test
    void verify_nullEvidences_handledGracefully() {
        VerificationResult result = verifier.verify("answer", null, "p1", null, AccessContext.PUBLIC);
        assertFalse(result.verified());
    }

    @Test
    void verify_sourceFileNull_reducesReliability() {
        EvidenceItem evWithSource = docEvidence("doc-1", "FileA.java", "src/FileA.java");
        EvidenceItem evNoSource = docEvidence("doc-2", "FileB", null);

        VerificationResult result = verifier.verify("FileA FileB", List.of(evWithSource, evNoSource), "p1", null, AccessContext.PUBLIC);

        // sourceFile 缺失降低可靠度，但不直接产生 violation
        // reliability = passRate(1.0)*0.6 + sourceRate(0.5)*0.4 = 0.6 + 0.2 = 0.8
        assertEquals(0.8, result.evidenceReliability(), 0.01);
    }

    @Test
    void verify_filePathClaimMatchesSourceFile() {
        EvidenceItem ev = docEvidence("doc-1", "doc", "src/main/java/OrderService.java");

        String answer = "参见 src/main/java/OrderService.java 中的实现。";
        VerificationResult result = verifier.verify(answer, List.of(ev), "p1", null, AccessContext.PUBLIC);

        assertTrue(result.evidenceCoverage() > 0);
        assertFalse(result.matchedClaims().isEmpty());
    }
}
