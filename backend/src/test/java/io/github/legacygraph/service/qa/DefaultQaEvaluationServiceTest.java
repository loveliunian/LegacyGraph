package io.github.legacygraph.service.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.EnhancedQaAgent;
import io.github.legacygraph.agent.QaAnswerResult;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.QaTestCase;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.QaTestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultQaEvaluationService} 单元测试 — 验证四项评估指标计算逻辑与门禁阈值判定。
 * EnhancedQaAgent 通过 Mockito mock，不依赖真实 LLM / 图谱。
 */
@ExtendWith(MockitoExtension.class)
class DefaultQaEvaluationServiceTest {

    @Mock
    private EnhancedQaAgent qaAgent;
    @Mock
    private QaTestCaseRepository qaTestCaseRepository;
    @Mock
    private CodeRepoRepository codeRepoRepository;

    private DefaultQaEvaluationService service;

    private static final String PROJECT_ID = "proj-test";
    private static final String VERSION_ID = "ver-test";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new DefaultQaEvaluationService(qaAgent, qaTestCaseRepository, codeRepoRepository, objectMapper);
        // 报告写到临时目录，避免污染用户 home
        ReflectionTestUtils.setField(service, "fallbackReportRoot",
                System.getProperty("java.io.tmpdir") + "/legacygraph-qa-test");
        // 无 CodeRepo → 走 fallback 目录（lenient：部分用例不触达报告写出）
        org.mockito.Mockito.lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
    }

    private QaTestCase testCase(String id, String question, List<String> entities,
                                List<String> keywords, boolean shouldAbstain, String intent) {
        QaTestCase tc = new QaTestCase();
        tc.setId(id);
        tc.setQuestion(question);
        tc.setExpectedEntities(entities);
        tc.setExpectedKeywords(keywords);
        tc.setShouldAbstain(shouldAbstain);
        tc.setIntent(intent);
        tc.setStatus("SMOKE");
        return tc;
    }

    private EvidenceItem evidence(String title, String excerpt, String sourceFile) {
        EvidenceItem ev = new EvidenceItem();
        ev.setTitle(title);
        ev.setExcerpt(excerpt);
        ev.setSourceFile(sourceFile);
        return ev;
    }

    // ==================== entityRecall ====================

    @Test
    void entityRecall_allEntitiesMentioned_returnsOne() {
        QaTestCase tc = testCase("t1", "lg_account 表有哪些字段？",
                List.of("lg_account", "balance"), List.of("账户", "字段"), false, "FACT_LOOKUP");
        when(qaAgent.answer(eq(PROJECT_ID), eq(VERSION_ID), eq(tc.getQuestion())))
                .thenReturn(new QaAnswerResult(
                        "lg_account 表包含 account_id、balance、account_name 等字段。",
                        List.of(evidence("lg_account", "账户表", "lg_account.sql")), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(1.0, result.getEntityRecall(), 1e-9);
        assertTrue(result.isPassed());
    }

    @Test
    void entityRecall_partialMatch_returnsFraction() {
        QaTestCase tc = testCase("t2", "q",
                List.of("lg_account", "balance", "nonexistent"), List.of("x"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 表有 balance 字段。",
                        List.of(), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        // 2/3 命中
        assertEquals(2.0 / 3.0, result.getEntityRecall(), 1e-9);
        // entityRecall≈0.667 < 0.85 → 门禁不通过
        assertFalse(result.isPassed());
        assertTrue(result.getFailureReasons().stream().anyMatch(r -> r.contains("ENTITY_RECALL")));
    }

    // ==================== evidencePrecision ====================

    @Test
    void evidencePrecision_mixedValidInvalid_returnsFraction() {
        QaTestCase tc = testCase("t3", "q",
                List.of("lg_account", "balance"), List.of("x"), false, "FACT_LOOKUP");
        // 3 条证据：2 条命中期望实体（有效），1 条不命中
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 表有 balance 字段。",
                        List.of(
                                evidence("lg_account", "账户表", "a.sql"),        // 命中 lg_account
                                evidence("balance 列", "余额", "b.sql"),         // 命中 balance
                                evidence("无关证据", "其它内容", "c.sql")          // 不命中
                        ), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        // 2/3 有效
        assertEquals(2.0 / 3.0, result.getEvidencePrecision(), 1e-9);
        assertFalse(result.isPassed());
        assertTrue(result.getFailureReasons().stream().anyMatch(r -> r.contains("EVIDENCE_PRECISION")));
    }

    @Test
    void evidencePrecision_allValid_returnsOne() {
        QaTestCase tc = testCase("t4", "q",
                List.of("lg_account"), List.of("x"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 表有字段。",
                        List.of(evidence("lg_account", "账户表", "a.sql")), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(1.0, result.getEvidencePrecision(), 1e-9);
    }

    // ==================== requiredKeywordCoverage ====================

    @Test
    void keywordCoverage_partialMatch_returnsFraction() {
        QaTestCase tc = testCase("t5", "q",
                List.of("x"), List.of("账户", "字段", "余额"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 是账户表。",  // 命中"账户"，未命中"字段""余额"
                        List.of(), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(1.0 / 3.0, result.getRequiredKeywordCoverage(), 1e-9);
    }

    // ==================== abstentionAccuracy ====================

    @Test
    void abstention_shouldAbstainAndAbstained_correct() {
        QaTestCase tc = testCase("t6", "q",
                List.of(), List.of(), true, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "图谱中没有找到相关信息，无法确定。", List.of(), 0.0, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(1.0, result.getAbstentionAccuracy(), 1e-9);
        assertTrue(result.isPassed());
    }

    @Test
    void abstention_shouldAbstainButAnswered_incorrect() {
        QaTestCase tc = testCase("t7", "q",
                List.of(), List.of(), true, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 表有 account_id 和 balance 两个字段，分别是主键和余额字段。",
                        List.of(), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        // 应拒答却回答 → abstentionAccuracy=0
        assertEquals(0.0, result.getAbstentionAccuracy(), 1e-9);
        assertFalse(result.isPassed());
        assertTrue(result.getFailureReasons().stream().anyMatch(r -> r.contains("ABSTENTION_ACCURACY")));
    }

    @Test
    void abstention_shouldAnswerAndAnswered_correct() {
        QaTestCase tc = testCase("t8", "q",
                List.of("lg_account"), List.of("账户"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 是账户表，包含 balance 字段。",
                        List.of(evidence("lg_account", "账户", "a.sql")), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(1.0, result.getAbstentionAccuracy(), 1e-9);
        assertTrue(result.isPassed());
    }

    @Test
    void abstention_shouldAnswerButAbstained_incorrect() {
        // 期望回答但模型拒答
        QaTestCase tc = testCase("t9", "q",
                List.of("lg_account"), List.of("账户"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult("无法回答，图谱中没有。", List.of(), 0.0, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertEquals(0.0, result.getAbstentionAccuracy(), 1e-9);
        assertFalse(result.isPassed());
    }

    // ==================== 聚合 & 门禁阈值 ====================

    @Test
    void gate_passesWhenAllThresholdsMet() {
        // 两条用例均通过
        QaTestCase tc1 = testCase("g1", "q1",
                List.of("lg_account", "balance"), List.of("账户"), false, "FACT_LOOKUP");
        QaTestCase tc2 = testCase("g2", "q2",
                List.of("OrderService"), List.of("订单"), false, "FACT_LOOKUP");
        when(qaAgent.answer(eq(PROJECT_ID), eq(VERSION_ID), eq("q1")))
                .thenReturn(new QaAnswerResult(
                        "lg_account 包含 balance 字段，是账户表。",
                        List.of(evidence("lg_account", "账户", "a.sql")), 0.9, false));
        when(qaAgent.answer(eq(PROJECT_ID), eq(VERSION_ID), eq("q2")))
                .thenReturn(new QaAnswerResult(
                        "OrderService 负责处理订单的创建、查询与状态流转等核心业务逻辑。",
                        List.of(evidence("OrderService", "订单服务", "b.java")), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc1, tc2));

        assertTrue(result.isPassed());
        assertEquals(2, result.getTotalCases());
        assertEquals(2, result.getPassedCases());
        assertEquals(2, result.getCaseResults().size());
    }

    @Test
    void gate_failsWhenEntityRecallBelowThreshold() {
        // 3 个期望实体，仅命中 2 个 → entityRecall=0.667 < 0.85
        QaTestCase tc = testCase("f1", "q",
                List.of("lg_account", "balance", "missing_entity_xyz"), List.of("x"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 有 balance 字段。",
                        List.of(evidence("lg_account", "账户", "a.sql")), 0.9, false));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        assertFalse(result.isPassed());
        assertTrue(result.getFailureReasons().stream().anyMatch(r -> r.contains("ENTITY_RECALL_BELOW_THRESHOLD")));
    }

    @Test
    void emptyTestCaseList_passesWithSkipNote() {
        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of());

        assertTrue(result.isPassed());
        assertEquals(0, result.getTotalCases());
        assertTrue(result.getFailureReasons().stream().anyMatch(r -> r.contains("NO_TEST_CASES")));
    }

    @Test
    void agentError_marksCaseFailed() {
        QaTestCase tc = testCase("e1", "q",
                List.of("lg_account"), List.of("账户"), false, "FACT_LOOKUP");
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult("", List.of(), 0.0, true));

        QaEvaluationResult result = service.evaluate(PROJECT_ID, VERSION_ID, List.of(tc));

        // 答案为空 → abstained=true，但 shouldAbstain=false → abstentionIncorrect
        assertEquals(0.0, result.getAbstentionAccuracy(), 1e-9);
        assertFalse(result.isPassed());
        // 单条用例记录失败原因含 agentError
        QaEvaluationResult.QaTestCaseResult cr = result.getCaseResults().get(0);
        assertFalse(cr.isPassed());
        assertNotNull(cr.getFailureReason());
        assertTrue(cr.getFailureReason().contains("agentError"));
    }

    // ==================== runSmoke ====================

    @Test
    void runSmoke_loadsSmokeCasesFromRepository() {
        QaTestCase smoke = testCase("s1", "lg_account 表有哪些字段？",
                List.of("lg_account", "balance"), List.of("账户", "字段"), false, "FACT_LOOKUP");
        smoke.setStatus("SMOKE");
        when(qaTestCaseRepository.selectList(any())).thenReturn(List.of(smoke));
        when(qaAgent.answer(any(), any(), any()))
                .thenReturn(new QaAnswerResult(
                        "lg_account 包含 balance 字段，是账户表。",
                        List.of(evidence("lg_account", "账户", "a.sql")), 0.9, false));

        QaEvaluationResult result = service.runSmoke(PROJECT_ID, VERSION_ID);

        assertTrue(result.isPassed());
        assertEquals(1, result.getTotalCases());
    }

    @Test
    void runSmoke_noSmokeCases_passesWithSkipNote() {
        when(qaTestCaseRepository.selectList(any())).thenReturn(List.of());

        QaEvaluationResult result = service.runSmoke(PROJECT_ID, VERSION_ID);

        assertTrue(result.isPassed());
        assertEquals(0, result.getTotalCases());
    }
}
