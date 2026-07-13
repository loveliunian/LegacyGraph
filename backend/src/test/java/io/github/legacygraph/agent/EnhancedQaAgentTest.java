package io.github.legacygraph.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.QaConversation;
import io.github.legacygraph.entity.QaMessage;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.change.ImpactSubgraphService;
import io.github.legacygraph.service.qa.ConversationContextManager;
import io.github.legacygraph.service.graph.GraphRagPlanExecutor;
import io.github.legacygraph.service.qa.HybridRetrievalService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.ReRankingService;
import io.github.legacygraph.service.qa.ConfidenceScorer;
import io.github.legacygraph.service.qa.EvidenceVerifier;
import io.github.legacygraph.service.qa.SemanticCache;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import io.github.legacygraph.repository.GraphReleaseRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.config.GraphReleaseConfig;
import io.github.legacygraph.dto.qa.ConfidenceBreakdown;
import io.github.legacygraph.dto.qa.VerificationResult;
import io.github.legacygraph.entity.SemanticCacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnhancedQaAgentTest {

    @Mock
    private ConversationContextManager conversationManager;
    @Mock
    private QueryIntentClassifier intentClassifier;
    @Mock
    private QueryRewriter queryRewriter;
    @Mock
    private HyDEGenerator hydeGenerator;
    @Mock
    private KnowledgeClaimService knowledgeClaimService;
    @Mock
    private HybridRetrievalService hybridRetrievalService;
    @Mock
    private ReRankingService reRankingService;
    @Mock
    private VectorRetrievalService vectorRetrievalService;
    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private LlmGateway llmGateway;
    @Mock
    private GraphRagPlannerAgent plannerAgent;
    @Mock
    private GraphRagPlanExecutor planExecutor;
    @Mock
    private SemanticCache semanticCache;
    @Mock
    private ImpactSubgraphService impactSubgraphService;
    @Mock
    private ChangeImpactAgent changeImpactAgent;
    @Mock
    private ChangeImpactQuestionParser changeImpactParser;
    @Mock
    private EvidenceVerifier evidenceVerifier;
    @Mock
    private ConfidenceScorer confidenceScorer;
    @Mock
    private GraphReleaseRepository graphReleaseRepository;
    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private GraphReleaseConfig graphReleaseConfig;
    @Mock
    private io.github.legacygraph.service.qa.AclFilterService aclFilterService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EnhancedQaAgent agent;
    private QaConversation conversation;
    private QaMessage assistantMessage;

    @BeforeEach
    void setUp() {
        agent = newAgent();

        conversation = new QaConversation();
        conversation.setId("conv-1");
        conversation.setProjectId("proj-1");

        assistantMessage = new QaMessage();
        assistantMessage.setId("assistant-1");
        assistantMessage.setConversationId("conv-1");
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setContent("answer");
    }

    @Test
    void streamAnswer_usesHydeInHybridRetrievalAndDoesPlanFactLookup() {
        stubConversationContext();
        when(semanticCache.getVersioned(eq("proj-1"), eq("OrderService 有哪些方法？"), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceVerifier.verify(anyString(), anyList(), anyString(), any(), any()))
                .thenReturn(new VerificationResult(true, 1.0, 1.0, List.of(), List.of(), List.of(), List.of()));
        when(confidenceScorer.score(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new ConfidenceBreakdown(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, ConfidenceBreakdown.Weights.DEFAULT));
        when(intentClassifier.classify(eq("proj-1"), anyString(), anyList()))
                .thenReturn(QueryIntent.FACT_LOOKUP);
        when(queryRewriter.rewrite(eq("proj-1"), anyString(), eq(QueryIntent.FACT_LOOKUP)))
                .thenReturn(List.of("OrderService method list"));
        when(hydeGenerator.generateHypotheticalDocument("proj-1", "OrderService 有哪些方法？"))
                .thenReturn("OrderService exposes createOrder and cancelOrder methods.");

        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setSubjectKey("OrderService");
        claim.setObjectKey("方法列表");
        when(knowledgeClaimService.listClaims("proj-1", "v1", null, null, null, null, 200))
                .thenReturn(List.of(claim));
        GraphRagPlan plan = GraphRagPlan.builder().needsHumanReview(false).build();
        when(plannerAgent.plan("proj-1", "OrderService 有哪些方法？", List.of(claim), QueryIntent.FACT_LOOKUP))
                .thenReturn(plan);
        when(planExecutor.execute(eq("proj-1"), eq("v1"), any(), any()))
                .thenReturn(GraphRagExecutionResult.builder().build());

        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        doc.setSourceUri("OrderService.java");
        doc.setContent("class OrderService { void createOrder() {} }");
        when(hybridRetrievalService.retrieve(eq("proj-1"), eq("v1"), anyString(), anyList(), eq(20), any(), any()))
                .thenReturn(List.of(doc));
        when(reRankingService.reRank(anyString(), anyList(), eq(10))).thenReturn(List.of(doc));
        when(vectorRetrievalService.semanticSearch(eq("proj-1"), eq("v1"), anyString(), eq(5), isNull()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            LlmGateway.StreamCallback callback = invocation.getArgument(3);
            callback.onToken("answer");
            callback.onComplete("answer");
            return null;
        }).when(llmGateway).callStream(eq("proj-1"), eq("qa-answer-enhanced"), any(), any());

        RecordingSseEmitter emitter = new RecordingSseEmitter();
        agent.answerStream("proj-1", "v1", "OrderService 有哪些方法？", null, emitter);

        verify(hydeGenerator).generateHypotheticalDocument("proj-1", "OrderService 有哪些方法？");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> variantsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hybridRetrievalService).retrieve(
                eq("proj-1"), eq("v1"), eq("OrderService 有哪些方法？"), variantsCaptor.capture(), eq(20), any(), any());
        assertTrue(variantsCaptor.getValue().contains("OrderService method list"));
        assertTrue(variantsCaptor.getValue().contains("OrderService exposes createOrder and cancelOrder methods."));
        verify(plannerAgent).plan("proj-1", "OrderService 有哪些方法？", List.of(claim), QueryIntent.FACT_LOOKUP);
    }

    @Test
    void streamAnswer_cacheHitStillPersistsConversationAndReturnsSavedMessageId() throws Exception {
        stubConversation();
        SemanticCacheEntry cacheEntry = new SemanticCacheEntry();
        cacheEntry.setAnswer("cached answer");
        cacheEntry.setConfidence(1.0);
        when(semanticCache.getVersioned(eq("proj-1"), eq("重复问题"), any(), any()))
                .thenReturn(Optional.of(cacheEntry));

        RecordingSseEmitter emitter = new RecordingSseEmitter();
        agent.answerStream("proj-1", "v1", "重复问题", null, emitter);

        verify(conversationManager).getOrCreateConversation("proj-1", null, null);
        verify(conversationManager).saveUserMessage("conv-1", "重复问题");
        verify(conversationManager).saveAssistantMessage(eq("conv-1"), eq("cached answer"), any(), eq(1.0));
        verify(llmGateway, never()).callStream(anyString(), anyString(), any(), any());

        Map<String, Object> complete = emitter.lastJsonEvent("complete");
        assertEquals("conv-1", complete.get("conversationId"));
        assertEquals("assistant-1", complete.get("messageId"));
        assertEquals(Boolean.TRUE, complete.get("fromCache"));
    }

    @Test
    void streamAnswer_structuralIntentPlansWithRelevantClaims() {
        stubConversationContext();
        when(semanticCache.getVersioned(eq("proj-1"), eq("订单创建涉及哪些表？"), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceVerifier.verify(anyString(), anyList(), anyString(), any(), any()))
                .thenReturn(new VerificationResult(true, 1.0, 1.0, List.of(), List.of(), List.of(), List.of()));
        when(confidenceScorer.score(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new ConfidenceBreakdown(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, ConfidenceBreakdown.Weights.DEFAULT));
        when(intentClassifier.classify(eq("proj-1"), anyString(), anyList()))
                .thenReturn(QueryIntent.STRUCTURAL);
        when(queryRewriter.rewrite(eq("proj-1"), anyString(), eq(QueryIntent.STRUCTURAL)))
                .thenReturn(List.of("订单创建 表"));
        when(hydeGenerator.generateHypotheticalDocument("proj-1", "订单创建涉及哪些表？")).thenReturn("");

        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setSubjectKey("feature:order-create");
        claim.setObjectKey("订单创建涉及哪些表");
        claim.setObjectValue("订单创建流程涉及的数据表");
        when(knowledgeClaimService.listClaims("proj-1", "v1", null, null, null, null, 200))
                .thenReturn(List.of(claim));
        GraphRagPlan plan = GraphRagPlan.builder().needsHumanReview(false).build();
        when(plannerAgent.plan("proj-1", "订单创建涉及哪些表？", List.of(claim), QueryIntent.STRUCTURAL)).thenReturn(plan);
        when(planExecutor.execute(eq("proj-1"), eq("v1"), any(), any()))
                .thenReturn(GraphRagExecutionResult.builder().build());
        when(hybridRetrievalService.retrieve(eq("proj-1"), eq("v1"), anyString(), anyList(), eq(20)))
                .thenReturn(List.of());
        when(reRankingService.reRank(anyString(), anyList(), eq(10))).thenReturn(List.of());
        when(vectorRetrievalService.semanticSearch(eq("proj-1"), eq("v1"), anyString(), eq(5), isNull()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            LlmGateway.StreamCallback callback = invocation.getArgument(3);
            callback.onComplete("answer");
            return null;
        }).when(llmGateway).callStream(eq("proj-1"), eq("qa-answer-enhanced"), any(), any());

        agent.answerStream("proj-1", "v1", "订单创建涉及哪些表？", null, new RecordingSseEmitter());

        verify(knowledgeClaimService).listClaims("proj-1", "v1", null, null, null, null, 200);
        verify(plannerAgent).plan("proj-1", "订单创建涉及哪些表？", List.of(claim), QueryIntent.STRUCTURAL);
    }

    private void stubConversation() {
        when(conversationManager.getOrCreateConversation("proj-1", null, null)).thenReturn(conversation);
        when(conversationManager.saveAssistantMessage(anyString(), anyString(), any(), any()))
                .thenReturn(assistantMessage);
    }

    private void stubConversationContext() {
        stubConversation();
        when(conversationManager.buildContextText("conv-1")).thenReturn("");
        when(conversationManager.getConversationHistory("conv-1")).thenReturn(List.of());
    }

    private EnhancedQaAgent newAgent() {
        boolean hasHydeDependency = Arrays.stream(EnhancedQaAgent.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(HyDEGenerator.class));
        assertTrue(hasHydeDependency, "EnhancedQaAgent must inject HyDEGenerator so P2 HyDE is not a dead class");
        boolean hasClaimDependency = Arrays.stream(EnhancedQaAgent.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(KnowledgeClaimService.class));
        assertTrue(hasClaimDependency, "EnhancedQaAgent must inject KnowledgeClaimService so GraphRAG Planner gets real claims");

        for (Constructor<?> constructor : EnhancedQaAgent.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 23) {
                try {
                    return (EnhancedQaAgent) constructor.newInstance(
                            conversationManager,
                            intentClassifier,
                            queryRewriter,
                            hydeGenerator,
                            knowledgeClaimService,
                            hybridRetrievalService,
                            reRankingService,
                            vectorRetrievalService,
                            neo4jGraphDao,
                            llmGateway,
                            plannerAgent,
                            planExecutor,
                            semanticCache,
                            objectMapper,
                            impactSubgraphService,
                            changeImpactAgent,
                            changeImpactParser,
                            evidenceVerifier,
                            confidenceScorer,
                            graphReleaseRepository,
                            solutionRepository,
                            graphReleaseConfig,
                            aclFilterService
                    );
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("EnhancedQaAgent constructor with 23 params was not found");
    }

    private class RecordingSseEmitter extends SseEmitter {
        private final List<String> chunks = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType data : builder.build()) {
                chunks.add(String.valueOf(data.getData()));
            }
        }

        Map<String, Object> lastJsonEvent(String eventName) throws Exception {
            String currentEvent = null;
            Map<String, Object> last = null;
            boolean awaitingDataBody = false;
            for (String chunk : chunks) {
                for (String line : chunk.split("\\n")) {
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                        awaitingDataBody = false;
                    } else if (line.startsWith("data:") && eventName.equals(currentEvent)) {
                        String json = line.substring("data:".length()).trim();
                        if (json.isEmpty()) {
                            awaitingDataBody = true;
                            continue;
                        }
                        last = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
                        awaitingDataBody = false;
                    } else if (awaitingDataBody && eventName.equals(currentEvent)) {
                        String json = line.trim();
                        if (json.isEmpty()) {
                            continue;
                        }
                        last = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
                        awaitingDataBody = false;
                    }
                }
            }
            if (last == null) {
                throw new AssertionError("Event not recorded: " + eventName + " in " + chunks);
            }
            return last;
        }
    }

    /**
     * 列举题意图识别：中文关键词 → NodeType。
     * <p>「哪些数据库表」「列出所有 Service」「有哪些 Controller」等应正确映射；
     * 非列举题或无类型词返回 null。
     * </p>
     */
    @Test
    void detectListingNodeType_mapsChineseKeywords() {
        EnhancedQaAgent a = newAgent();
        assertEquals("Table", a.detectListingNodeType("这个项目用了哪些数据库表？"));
        assertEquals("Table", a.detectListingNodeType("列出所有数据表"));
        assertEquals("Table", a.detectListingNodeType("系统有哪些表名"));
        assertEquals("Table", a.detectListingNodeType("系统里都有哪些表"));
        assertEquals("Service", a.detectListingNodeType("有哪些 Service 类"));
        assertEquals("Service", a.detectListingNodeType("列出所有服务"));
        assertEquals("Controller", a.detectListingNodeType("一共有多少个控制器"));
        assertEquals("Mapper", a.detectListingNodeType("项目有哪些 Mapper"));
        assertEquals("ApiEndpoint", a.detectListingNodeType("列出所有接口"));
        assertEquals("ApiEndpoint", a.detectListingNodeType("OrderController 处理哪些 API"));
        assertEquals("BusinessDomain", a.detectListingNodeType("有哪些业务域"));
        // 含列举词但无类型词 → null
        assertNull(a.detectListingNodeType("订单域包含哪些功能"));
        // 无列举词 → null
        assertNull(a.detectListingNodeType("系统架构是什么样的"));
        assertNull(a.detectListingNodeType("OrderController 是做什么的"));
    }
}
