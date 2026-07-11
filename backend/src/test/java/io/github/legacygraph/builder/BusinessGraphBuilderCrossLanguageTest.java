package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.terminology.TerminologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * G5 跨语言实体消解 embedding 增强测试。
 *
 * <p>验证 {@link BusinessGraphBuilder#mergeCrossLanguageFeatures} 在 Feature Flag
 * {@code legacygraph.cross-language.embedding.enabled} 开/关时的行为：
 * <ul>
 *   <li>flag 关闭 → 名称匹配降级路径（TerminologyService.calculateSimilarity）</li>
 *   <li>flag 开启且余弦相似度 > 0.78 → 创建 POSSIBLE_SAME_AS 边，confidence = 相似度</li>
 *   <li>flag 开启但余弦相似度 < 0.78 → 不创建边</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
class BusinessGraphBuilderCrossLanguageTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private EvidenceGraphWriter writer;
    @Mock
    private TerminologyService terminologyService;
    @Mock
    private EmbeddingModel embeddingModel;

    private BusinessGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new BusinessGraphBuilder(neo4jGraphDao, docChunkRepository, writer,
                new FeatureIdentityNormalizer(), terminologyService);
        ReflectionTestUtils.setField(builder, "embeddingModel", embeddingModel);
    }

    /** 构建带 id/nodeType/nodeKey/displayName/nodeName/sourceType 的 Feature 测试节点 */
    private GraphNode newFeature(String id, String nodeKey, String name, String sourceType) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeType(NodeType.Feature.name());
        n.setNodeKey(nodeKey);
        n.setNodeName(name);
        n.setDisplayName(name);
        n.setSourceType(sourceType);
        return n;
    }

    /**
     * flag 关闭时只做名称匹配：不调用 embedding 服务，通过 TerminologyService 跨语言相似度创建候选边。
     * confidence 应记录名称相似度值。
     */
    @Test
    void mergeWithEmbeddingDisabledUsesNameMatchOnly() {
        // flag 关闭（默认 false）→ 走名称匹配降级路径
        ReflectionTestUtils.setField(builder, "crossLanguageEmbeddingEnabled", false);

        GraphNode docFeat = newFeature("doc-1", "feature:释放会员保证金", "释放会员保证金", "DOC_AI");
        GraphNode feFeat = newFeature("fe-1", "feature:unlock", "unLock", "FRONTEND_AST");
        when(neo4jGraphDao.queryNodes(eq("p1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(docFeat, feFeat));
        when(terminologyService.calculateSimilarity(anyString(), anyString())).thenReturn(0.85);
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenReturn(new GraphEdge());

        int merged = builder.mergeCrossLanguageFeatures("p1", "v1");

        assertEquals(1, merged, "名称匹配应创建 1 组候选");
        // embedding 路径不应被调用
        verify(embeddingModel, never()).embed(anyList());
        verify(embeddingModel, never()).embed(anyString());
        // 验证名称匹配确实发生
        verify(terminologyService).calculateSimilarity(anyString(), anyString());
        // 验证边类型为 POSSIBLE_SAME_AS，confidence = 名称相似度
        ArgumentCaptor<GraphEdgeClaim> captor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer).upsertEdge(captor.capture());
        GraphEdgeClaim claim = captor.getValue();
        assertEquals(EdgeType.POSSIBLE_SAME_AS.name(), claim.getEdgeType());
        assertEquals("AI_FEATURE_MAPPING", claim.getSourceType());
        assertEquals("PENDING_CONFIRM", claim.getStatus());
        assertEquals(0.85, claim.getConfidence().doubleValue(), 1e-9,
                "confidence 应记录名称相似度值");
    }

    /**
     * flag 开启且余弦相似度 > 0.78 时创建 POSSIBLE_SAME_AS 边。
     * confidence 应记录 embedding 相似度值。
     */
    @Test
    void mergeWithEmbeddingEnabledCreatesPossibleSameAsEdge() {
        // flag 开启 → 走 embedding 语义匹配路径
        ReflectionTestUtils.setField(builder, "crossLanguageEmbeddingEnabled", true);

        GraphNode docFeat = newFeature("doc-1", "feature:订单创建", "订单创建", "DOC_AI");
        GraphNode feFeat = newFeature("fe-1", "feature:create-order", "create order", "FRONTEND_AST");
        when(neo4jGraphDao.queryNodes(eq("p1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(docFeat, feFeat));
        // 两个名称 embedding 完全相同 → cosine = 1.0 > 0.78 阈值
        when(embeddingModel.embed(anyList())).thenReturn(
                List.of(new float[]{1f, 0f}, new float[]{1f, 0f}));
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenReturn(new GraphEdge());

        int merged = builder.mergeCrossLanguageFeatures("p1", "v1");

        assertEquals(1, merged, "embedding 匹配应创建 1 组候选");
        verify(embeddingModel, atLeastOnce()).embed(anyList());
        // 验证边类型为 POSSIBLE_SAME_AS，confidence = embedding 相似度（1.0）
        ArgumentCaptor<GraphEdgeClaim> captor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer).upsertEdge(captor.capture());
        GraphEdgeClaim claim = captor.getValue();
        assertEquals(EdgeType.POSSIBLE_SAME_AS.name(), claim.getEdgeType());
        assertEquals("AI_FEATURE_MAPPING", claim.getSourceType());
        assertEquals("PENDING_CONFIRM", claim.getStatus());
        assertEquals(1.0, claim.getConfidence().doubleValue(), 1e-9,
                "confidence 应记录 embedding 相似度值");
    }

    /**
     * flag 开启但余弦相似度 < 0.78 时不创建边。
     */
    @Test
    void mergeWithEmbeddingBelowThresholdDoesNotCreateEdge() {
        // flag 开启但相似度 < 0.78 → 不创建边
        ReflectionTestUtils.setField(builder, "crossLanguageEmbeddingEnabled", true);

        GraphNode docFeat = newFeature("doc-1", "feature:订单创建", "订单创建", "DOC_AI");
        GraphNode feFeat = newFeature("fe-1", "feature:login", "login", "FRONTEND_AST");
        when(neo4jGraphDao.queryNodes(eq("p1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(docFeat, feFeat));
        // 两个名称 embedding 正交 → cosine = 0.0 < 0.78 阈值
        when(embeddingModel.embed(anyList())).thenReturn(
                List.of(new float[]{1f, 0f}, new float[]{0f, 1f}));

        int merged = builder.mergeCrossLanguageFeatures("p1", "v1");

        assertEquals(0, merged, "相似度低于阈值不应创建边");
        verify(writer, never()).upsertEdge(any(GraphEdgeClaim.class));
    }
}
