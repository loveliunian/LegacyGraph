package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReviewAgent 测试 — Phase 0 契约对齐后，使用独立的 review-suggestion 模板。
 */
@ExtendWith(MockitoExtension.class)
class ReviewAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private ReviewAgent reviewAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    @Test
    void testConstruction() {
        assertNotNull(reviewAgent);
    }

    @Test
    void testGenerateReviewSuggestion_UsesDedicatedTemplate() {
        // given
        ReviewAgent.ReviewRequest request = new ReviewAgent.ReviewRequest();
        request.setProjectId("proj-1");
        request.setTargetType("NODE");
        request.setTargetDescription("用户注册业务流程节点");
        request.setSupportingEvidence(List.of("代码中存在 register 方法", "文档描述注册流程"));
        request.setConflictingEvidence(List.of("缺少邮箱校验证据"));
        request.setCurrentConfidence(0.6);

        ReviewAgent.ReviewResult expected = new ReviewAgent.ReviewResult();
        expected.setSummary("证据基本充分");
        expected.setRecommendation(ReviewAgent.ReviewResult.Recommendation.NEED_MORE_INFO);

        when(llmGateway.callWithTemplate(
                eq("proj-1"), eq("review-suggestion"),
                anyMap(), eq(ReviewAgent.ReviewResult.class)))
                .thenReturn(expected);

        // when
        ReviewAgent.ReviewResult result = reviewAgent.generateReviewSuggestion(request);

        // then
        assertNotNull(result);
        assertEquals(ReviewAgent.ReviewResult.Recommendation.NEED_MORE_INFO, result.getRecommendation());

        verify(llmGateway).callWithTemplate(
                eq("proj-1"), eq("review-suggestion"),
                variablesCaptor.capture(), eq(ReviewAgent.ReviewResult.class));

        Map<String, String> vars = variablesCaptor.getValue();
        assertEquals("NODE", vars.get("targetType"));
        assertEquals("用户注册业务流程节点", vars.get("targetDescription"));
        assertEquals("0.6", vars.get("currentConfidence"));
        assertTrue(vars.get("supportingEvidence").contains("register"));
        assertTrue(vars.get("conflictingEvidence").contains("邮箱校验"));
    }
}
