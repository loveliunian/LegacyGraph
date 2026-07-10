package io.github.legacygraph.service.requirement;

import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RequirementExtractionService} 单元测试（mock LlmGateway）。
 */
@ExtendWith(MockitoExtension.class)
class RequirementExtractionServiceTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private RequirementExtractionService service;

    private static final String PROJECT_ID = "proj-001";
    private static final String TEMPLATE = "requirement-analysis";

    private RequirementAnalysis buildSample() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("支持按最近30天导出t_order订单数据为Excel");

        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("系统将t_order表最近30天的订单导出为Excel");
        item.setAcceptanceCriteria(List.of("导出文件为Excel格式", "导出字段包含订单号、金额、下单时间"));
        item.setConstraints(List.of("单次导出不超过5万条"));
        analysis.setItems(List.of(item));
        analysis.setOpenQuestions(List.of());
        return analysis;
    }

    @Test
    void extract_happyPath_returnsStructuredResult() {
        String text = "系统将t_order表最近30天的订单导出为Excel，单次不超过5万条。";
        RequirementAnalysis expected = buildSample();
        when(llmGateway.callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class)))
                .thenReturn(expected);

        RequirementAnalysis actual = service.extract(PROJECT_ID, text);

        assertNotNull(actual);
        assertEquals("支持按最近30天导出t_order订单数据为Excel", actual.getGoal());
        assertNotNull(actual.getItems());
        assertEquals(1, actual.getItems().size());
        RequirementItemDTO item = actual.getItems().get(0);
        assertEquals("R1", item.getCode());
        assertEquals(2, item.getAcceptanceCriteria().size());
        assertEquals(1, item.getConstraints().size());
        assertNotNull(actual.getOpenQuestions());

        // 验证 LlmGateway 调用参数：模板名 + variables 含 requirementText
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID),
                eq(TEMPLATE),
                argThat((Map<String, String> vars) ->
                        text.equals(vars.get("requirementText")) && vars.size() == 1),
                eq(RequirementAnalysis.class));
    }

    @Test
    void extract_blankText_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.extract(PROJECT_ID, ""));
        assertThrows(IllegalArgumentException.class, () -> service.extract(PROJECT_ID, "   "));
        assertThrows(IllegalArgumentException.class, () -> service.extract(PROJECT_ID, null));

        // LLM 不应被调用
        verifyNoInteractions(llmGateway);
    }

    @Test
    void extract_llmReturnsNull_fillsEmptyDefaults() {
        when(llmGateway.callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class)))
                .thenReturn(null);

        RequirementAnalysis actual = service.extract(PROJECT_ID, "某需求");

        assertNotNull(actual);
        assertNotNull(actual.getItems());
        assertTrue(actual.getItems().isEmpty());
        assertNotNull(actual.getOpenQuestions());
        assertTrue(actual.getOpenQuestions().isEmpty());
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class));
    }

    @Test
    void extract_llmReturnsNullFields_fillsEmptyCollections() {
        RequirementAnalysis partial = new RequirementAnalysis();
        partial.setGoal("目标");
        // items / openQuestions 均为 null
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("条目");
        // acceptanceCriteria / constraints 均为 null
        partial.setItems(List.of(item));
        partial.setOpenQuestions(null);

        when(llmGateway.callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class)))
                .thenReturn(partial);

        RequirementAnalysis actual = service.extract(PROJECT_ID, "某需求");

        assertNotNull(actual.getOpenQuestions());
        assertTrue(actual.getOpenQuestions().isEmpty());
        assertEquals(1, actual.getItems().size());
        RequirementItemDTO dto = actual.getItems().get(0);
        assertNotNull(dto.getAcceptanceCriteria());
        assertTrue(dto.getAcceptanceCriteria().isEmpty());
        assertNotNull(dto.getConstraints());
        assertTrue(dto.getConstraints().isEmpty());
    }

    @Test
    void extract_llmFailure_propagatesException() {
        when(llmGateway.callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class)))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.extract(PROJECT_ID, "某需求"));
        assertTrue(ex.getMessage().contains("LLM 调用失败"));
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class));
    }

    @Test
    void extract_emptyItems_preservedAsEmptyList() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("");
        analysis.setItems(List.of());
        analysis.setOpenQuestions(List.of("数据来源未明确"));
        when(llmGateway.callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(RequirementAnalysis.class)))
                .thenReturn(analysis);

        RequirementAnalysis actual = service.extract(PROJECT_ID, "我们需要一个报表功能");

        assertEquals("", actual.getGoal());
        assertTrue(actual.getItems().isEmpty());
        assertEquals(1, actual.getOpenQuestions().size());
        assertEquals("数据来源未明确", actual.getOpenQuestions().get(0));
    }
}
