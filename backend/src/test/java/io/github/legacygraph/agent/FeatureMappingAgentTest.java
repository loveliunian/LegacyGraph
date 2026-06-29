package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FeatureMappingAgentTest - 功能映射对齐 Agent 单元测试
 *
 * 覆盖场景：
 * 1. 正常映射流程：所有字段齐备，验证 LLM 调用参数和返回结果
 * 2. 空字段处理：部分字段为 null，验证缺省为空字符串
 * 3. 映射结果包含冲突项
 */
@ExtendWith(MockitoExtension.class)
class FeatureMappingAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private FeatureMappingAgent featureMappingAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    private FeatureMappingAgent.MappingRequest fullRequest;
    private FeatureMappingAgent.MappingResult expectedResult;

    @BeforeEach
    void setUp() {
        fullRequest = new FeatureMappingAgent.MappingRequest();
        fullRequest.setProjectId("project-001");
        fullRequest.setVueCode("<template><button @click=\"submit\">提交</button></template>");
        fullRequest.setApiDefinitions("POST /api/order/submit");
        fullRequest.setControllerCode("@PostMapping(\"/submit\") public Result submitOrder(@RequestBody OrderDTO dto)");
        fullRequest.setPermissionInfo("@PreAuthorize(\"hasPermission('order:submit')\")");
        fullRequest.setProductDoc("订单提交功能：用户填写信息后点击提交按钮");
    }

    @Test
    void testMapFeatures_NormalFlow() {
        // given: 准备预期返回结果
        expectedResult = new FeatureMappingAgent.MappingResult();
        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setPageKey("order-create");
        mapping.setButtonName("提交");
        mapping.setApiKey("POST /api/order/submit");
        mapping.setBusinessAction("创建订单");
        mapping.setPermissionKey("order:submit");
        mapping.setConfidence(0.95);
        mapping.setEvidence(new ArrayList<>());
        mapping.setConflicts(new ArrayList<>());
        expectedResult.getMappings().add(mapping);

        when(llmGateway.callWithTemplate(
                eq("project-001"), eq("feature-mapping"),
                anyMap(), eq(FeatureMappingAgent.MappingResult.class)))
                .thenReturn(expectedResult);

        // when
        FeatureMappingAgent.MappingResult result = featureMappingAgent.mapFeatures(fullRequest);

        // then: 验证返回结果
        assertNotNull(result);
        assertEquals(1, result.getMappings().size());
        assertEquals("order-create", result.getMappings().get(0).getPageKey());
        assertEquals("提交", result.getMappings().get(0).getButtonName());
        assertEquals("POST /api/order/submit", result.getMappings().get(0).getApiKey());
        assertEquals("创建订单", result.getMappings().get(0).getBusinessAction());
        assertEquals("order:submit", result.getMappings().get(0).getPermissionKey());
        assertEquals(0.95, result.getMappings().get(0).getConfidence(), 0.001);
        assertTrue(result.getUnmatched().isEmpty());

        // 验证 LLM 调用参数
        verify(llmGateway).callWithTemplate(
                eq("project-001"), eq("feature-mapping"),
                variablesCaptor.capture(), eq(FeatureMappingAgent.MappingResult.class));

        Map<String, String> capturedVars = variablesCaptor.getValue();
        assertNotNull(capturedVars);
        assertEquals(fullRequest.getVueCode(), capturedVars.get("vueCode"));
        assertEquals(fullRequest.getApiDefinitions(), capturedVars.get("apiDefinitions"));
        assertEquals(fullRequest.getControllerCode(), capturedVars.get("controllerCode"));
        assertEquals(fullRequest.getPermissionInfo(), capturedVars.get("permissionInfo"));
        assertEquals(fullRequest.getProductDoc(), capturedVars.get("productDoc"));
    }

    @Test
    void testMapFeatures_PartialNullFields_DefaultsToEmpty() {
        // given: 部分字段为 null
        FeatureMappingAgent.MappingRequest partialRequest = new FeatureMappingAgent.MappingRequest();
        partialRequest.setProjectId("project-002");
        partialRequest.setVueCode("<button>确认</button>");
        partialRequest.setControllerCode(null); // 故意设 null
        partialRequest.setApiDefinitions(null);
        partialRequest.setPermissionInfo(null);
        partialRequest.setProductDoc(null);

        // LLM 返回空结果
        expectedResult = new FeatureMappingAgent.MappingResult();
        when(llmGateway.callWithTemplate(
                eq("project-002"), eq("feature-mapping"),
                anyMap(), eq(FeatureMappingAgent.MappingResult.class)))
                .thenReturn(expectedResult);

        // when
        FeatureMappingAgent.MappingResult result = featureMappingAgent.mapFeatures(partialRequest);

        // then: 返回非空对象，无映射项
        assertNotNull(result);
        assertTrue(result.getMappings().isEmpty());
        assertTrue(result.getUnmatched().isEmpty());

        // 验证传入 LLM 的变量中，null 字段被转为空字符串
        verify(llmGateway).callWithTemplate(
                eq("project-002"), eq("feature-mapping"),
                variablesCaptor.capture(), eq(FeatureMappingAgent.MappingResult.class));

        Map<String, String> capturedVars = variablesCaptor.getValue();
        assertEquals(partialRequest.getVueCode(), capturedVars.get("vueCode"));
        assertEquals("", capturedVars.get("apiDefinitions"));
        assertEquals("", capturedVars.get("controllerCode"));
        assertEquals("", capturedVars.get("permissionInfo"));
        assertEquals("", capturedVars.get("productDoc"));
    }

    @Test
    void testMapFeatures_ResultWithConflictsAndUnmatched() {
        // given: 返回结果包含冲突项和未匹配项
        expectedResult = new FeatureMappingAgent.MappingResult();

        // 正常映射
        FeatureMappingAgent.Mapping mapping1 = new FeatureMappingAgent.Mapping();
        mapping1.setPageKey("user-list");
        mapping1.setButtonName("删除");
        mapping1.setApiKey("DELETE /api/user/{id}");
        mapping1.setBusinessAction("删除用户");
        mapping1.setPermissionKey("user:delete");
        mapping1.setConfidence(0.92);
        mapping1.setEvidence(Collections.singletonList(
                Map.of("source", "controller", "detail", "@DeleteMapping(\"/user/{id}\")")));
        mapping1.setConflicts(new ArrayList<>());
        expectedResult.getMappings().add(mapping1);

        // 存在冲突的映射（相同按钮映射到不同 API）
        FeatureMappingAgent.Mapping mapping2 = new FeatureMappingAgent.Mapping();
        mapping2.setPageKey("user-list");
        mapping2.setButtonName("删除");
        mapping2.setApiKey("POST /api/user/batch-delete");
        mapping2.setBusinessAction("批量删除用户");
        mapping2.setPermissionKey("user:batch-delete");
        mapping2.setConfidence(0.65);
        mapping2.setEvidence(new ArrayList<>());
        mapping2.setConflicts(List.of("与主映射冲突：DELETE /api/user/{id}"));
        expectedResult.getMappings().add(mapping2);

        // 未匹配的按钮
        expectedResult.getUnmatched().add("批量导入");
        expectedResult.getUnmatched().add("导出报表");

        when(llmGateway.callWithTemplate(
                eq("project-001"), eq("feature-mapping"),
                anyMap(), eq(FeatureMappingAgent.MappingResult.class)))
                .thenReturn(expectedResult);

        // when
        FeatureMappingAgent.MappingResult result = featureMappingAgent.mapFeatures(fullRequest);

        // then: 验证返回了 2 个映射
        assertNotNull(result);
        assertEquals(2, result.getMappings().size());

        // 第一个映射：高置信度主映射
        FeatureMappingAgent.Mapping first = result.getMappings().get(0);
        assertEquals("user-list", first.getPageKey());
        assertEquals("删除", first.getButtonName());
        assertEquals("DELETE /api/user/{id}", first.getApiKey());
        assertEquals(0.92, first.getConfidence(), 0.001);
        // 主映射本身无冲突（mapping1 的 conflicts 设为空列表）
        assertTrue(first.getConflicts().isEmpty());
        assertEquals(1, first.getEvidence().size());

        // 第二个映射：低置信度冲突映射
        FeatureMappingAgent.Mapping second = result.getMappings().get(1);
        assertEquals("POST /api/user/batch-delete", second.getApiKey());
        assertEquals(0.65, second.getConfidence(), 0.001);
        assertTrue(second.getConflicts().contains("与主映射冲突：DELETE /api/user/{id}"));

        // 验证未匹配项
        assertEquals(2, result.getUnmatched().size());
        assertTrue(result.getUnmatched().contains("批量导入"));
        assertTrue(result.getUnmatched().contains("导出报表"));
    }
}
