package io.github.legacygraph.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jWriteRepository;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.repository.QaFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4-T2: QaFeedbackService evidenceScore 回写测试。
 * 验证：
 * - 👍 正反馈：答案中引用的 [节点ID] 各 +1
 * - 👎 负反馈：引用的节点各 -1
 * - 无 Neo4jWriteRepository 时降级（不抛异常）
 * - 空答案 / 无引用节点时不调 Neo4j
 */
class QaFeedbackServiceEvidenceScoreTest {

    private QaFeedbackRepository repo;
    private Neo4jWriteRepository neo4jWriteRepo;
    private QaFeedbackService service;

    @BeforeEach
    void setUp() {
        repo = mock(QaFeedbackRepository.class);
        neo4jWriteRepo = mock(Neo4jWriteRepository.class);
        service = new QaFeedbackService(repo);
        ReflectionTestUtils.setField(service, "neo4jWriteRepository", neo4jWriteRepo);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void recordFeedback_positiveHelpful_incrementsEvidenceScore() {
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenReturn(1);

        QaFeedback feedback = service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                true,
                "如何退款",
                "用户退款请走 UserService（[node-abc12345]），由 RefundService（[node-xyz98765]）处理",
                "回答正确"
        );

        assertNotNull(feedback);
        assertEquals(true, feedback.getHelpful());
        // 验证 Neo4j 被调用两次（每个引用的节点一次），delta=+1
        verify(neo4jWriteRepo, times(2)).executeWriteQuery(anyString(), anyMap());
    }

    @Test
    void recordFeedback_negativeHelpful_decrementsEvidenceScore() {
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenReturn(1);

        service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                false,
                "如何退款",
                "请走 [node-abc12345] 处理",
                "答错了"
        );

        // 负反馈 delta=-1
        verify(neo4jWriteRepo, times(1)).executeWriteQuery(anyString(), anyMap());
    }

    @Test
    void recordFeedback_noCitedNodes_skipsNeo4jCall() {
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenReturn(1);

        service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                true,
                "你好",
                "你好！很高兴为你服务",  // 无 [节点ID] 引用
                null
        );

        // 无节点引用时不调 Neo4j
        verify(neo4jWriteRepo, org.mockito.Mockito.never())
                .executeWriteQuery(anyString(), anyMap());
    }

    @Test
    void recordFeedback_neo4jUnavailable_doesNotThrow() {
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("Neo4j down"))
                .when(neo4jWriteRepo).executeWriteQuery(anyString(), anyMap());

        // Neo4j 不可用时，记录不应抛异常（降级到仅写 DB）
        QaFeedback feedback = service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                true,
                "如何退款",
                "请走 [node-abc12345] 处理",
                null
        );

        assertNotNull(feedback);
        // 即便 Neo4j 报错，feedback 仍记录成功
        assertEquals(true, feedback.getHelpful());
    }

    @Test
    void recordFeedback_nullNeo4jRepo_degradesGracefully() {
        // 把 Neo4jWriteRepository 设为 null（生产环境未注入）
        ReflectionTestUtils.setField(service, "neo4jWriteRepository", null);
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenReturn(1);

        // 不抛异常
        QaFeedback feedback = service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                true,
                "如何退款",
                "请走 [node-abc12345] 处理",
                null
        );

        assertNotNull(feedback);
        assertTrue(feedback.getHelpful());
    }

    @Test
    void recordFeedback_usesUsedEvidenceIdsJson() {
        // 场景：usedEvidenceIds 字段直接是 JSON 数组（不走 answer 文本提取）
        when(repo.insert(org.mockito.ArgumentMatchers.any(QaFeedback.class)))
                .thenAnswer(invocation -> {
                    QaFeedback f = invocation.getArgument(0);
                    f.setId("feedback-1");
                    // 直接调用 setter（void 返回），不让 thenAnswer 嵌套链式
                    f.setUsedEvidenceIds("[\"node-evidence-001\",\"node-evidence-002\"]");
                    return 1;
                });

        service.recordFeedback(
                "conv-1", "msg-1", "proj-1",
                true,
                "查询",
                "答案文本",  // 无 [节点ID] 引用
                null
        );

        // 从 usedEvidenceIds 提取的两个节点都应该被 +1
        verify(neo4jWriteRepo, times(2))
                .executeWriteQuery(anyString(), anyMap());
    }
}