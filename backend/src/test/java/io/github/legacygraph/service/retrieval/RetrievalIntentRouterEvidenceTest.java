package io.github.legacygraph.service.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4-T1: RetrievalIntentRouter 强制证据回填测试。
 * 验证：
 * - 有证据节点时 prompt 必须嵌入节点 ID
 * - 闲聊白名单跳过证据回填（避免命中率断崖）
 * - 答案未引用节点 ID 时 enforceEvidenceCitation 返回 false
 * - 答案引用任一节点 ID 时返回 true
 */
class RetrievalIntentRouterEvidenceTest {

    private RetrievalIntentRouter router;

    @BeforeEach
    void setUp() {
        router = new RetrievalIntentRouter();
    }

    @Test
    void buildEvidenceBackedPrompt_withGraphNodes_embedsNodeIds() {
        String query = "用户服务怎么处理退款";
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService", "nodeType", "Service"),
                Map.of("id", "node-002", "nodeName", "RefundService", "nodeType", "Service")
        );

        String prompt = router.buildEvidenceBackedPrompt(query, RetrievalIntentRouter.Intent.CODE_LOOKUP, nodes);

        assertTrue(prompt.contains("node-001"), "prompt 应嵌入节点 ID node-001");
        assertTrue(prompt.contains("node-002"), "prompt 应嵌入节点 ID node-002");
        assertTrue(prompt.contains("UserService"), "prompt 应嵌入节点名");
        assertTrue(prompt.contains(query), "prompt 应包含原始 query");
    }

    @Test
    void buildEvidenceBackedPrompt_emptyGraphNodes_returnsOriginalQuery() {
        // 没有证据节点 — 返回原始 query，避免误导 LLM
        String query = "用户服务怎么处理退款";
        String prompt = router.buildEvidenceBackedPrompt(query, RetrievalIntentRouter.Intent.CODE_LOOKUP, List.of());
        assertEquals(query, prompt, "无证据节点时应返回原始 query");
    }

    @Test
    void buildEvidenceBackedPrompt_chitchat_returnsOriginalQuery() {
        // 闲聊型白名单 — 跳过证据回填
        String query = "你好";
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService", "nodeType", "Service")
        );

        String prompt = router.buildEvidenceBackedPrompt(query, RetrievalIntentRouter.Intent.GENERAL, nodes);
        assertEquals(query, prompt, "GENERAL 意图应跳过证据回填");
    }

    @Test
    void enforceEvidenceCitation_answerContainsNodeId_returnsTrue() {
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService")
        );
        String answer = "用户服务（[node-001]）负责处理退款逻辑";

        assertTrue(router.enforceEvidenceCitation(
                answer, RetrievalIntentRouter.Intent.CODE_LOOKUP, "用户退款", nodes),
                "答案引用了节点 ID 应通过校验");
    }

    @Test
    void enforceEvidenceCitation_answerLacksNodeId_returnsFalse() {
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService")
        );
        String answer = "用户服务负责处理退款逻辑"; // 没引用 [node-001]

        assertFalse(router.enforceEvidenceCitation(
                answer, RetrievalIntentRouter.Intent.CODE_LOOKUP, "用户退款", nodes),
                "答案未引用节点 ID 应校验失败（触发重试/降级）");
    }

    @Test
    void enforceEvidenceCitation_chitchat_returnsTrue() {
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService")
        );
        String answer = "你好呀！"; // 没引用节点，但闲聊场景放行

        assertTrue(router.enforceEvidenceCitation(
                answer, RetrievalIntentRouter.Intent.GENERAL, "你好", nodes),
                "闲聊场景跳过校验");
    }

    @Test
    void enforceEvidenceCitation_emptyGraphNodes_returnsFalse() {
        // 有图谱证据要求但实际未检索到 — 校验失败
        assertFalse(router.enforceEvidenceCitation(
                "任意答案", RetrievalIntentRouter.Intent.CODE_LOOKUP, "查询", List.of()),
                "无证据节点应校验失败");
    }

    @Test
    void enforceEvidenceCitation_blankAnswer_returnsFalse() {
        var nodes = List.<Map<String, Object>>of(
                Map.of("id", "node-001", "nodeName", "UserService")
        );
        assertFalse(router.enforceEvidenceCitation(
                "", RetrievalIntentRouter.Intent.CODE_LOOKUP, "查询", nodes),
                "空答案应校验失败");
    }
}