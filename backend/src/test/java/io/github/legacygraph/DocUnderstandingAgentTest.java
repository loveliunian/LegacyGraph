package io.github.legacygraph;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.DocUnderstandingAgent.BusinessDomain;
import io.github.legacygraph.agent.DocUnderstandingAgent.BusinessFactExtraction;
import io.github.legacygraph.agent.DocUnderstandingAgent.BusinessObject;
import io.github.legacygraph.agent.DocUnderstandingAgent.BusinessProcess;
import io.github.legacygraph.agent.DocUnderstandingAgent.BusinessRule;
import io.github.legacygraph.agent.DocUnderstandingAgent.EvidenceRef;
import io.github.legacygraph.agent.DocUnderstandingAgent.StatusTransition;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * 测试 {@link DocUnderstandingAgent} 的业务事实抽取与置信度计算功能
 * <p>
 * 覆盖以下场景：
 * <ol>
 *   <li>extractBusinessFacts — 验证正确调用 LlmGateway 并返回抽取结果</li>
 *   <li>calculateAverageConfidence — 包含多种业务实体的混合置信度计算</li>
 *   <li>calculateAverageConfidence — 无任何实体的空结果置信度计算</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DocUnderstandingAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private DocUnderstandingAgent agent;

    // ==================== 用例 1：extractBusinessFacts ====================

    @Test
    @DisplayName("调用 extractBusinessFacts 应正确委托 LlmGateway 并返回抽取结果")
    void shouldExtractBusinessFactsViaLlmGateway() {
        // 准备
        String projectId = "project-001";
        String docContent = "订单系统：用户下单后，系统验证库存，生成订单，通知仓库发货。";
        String sourcePath = "/docs/order-system.md";

        BusinessFactExtraction expected = new BusinessFactExtraction();
        // 流程
        BusinessProcess process = new BusinessProcess();
        process.setKey("order-flow");
        process.setName("订单处理流程");
        process.setDescription("从用户下单到仓库发货的完整流程");
        process.setSteps(List.of("用户提交订单", "系统验证库存", "生成订单", "通知仓库发货"));
        process.setRoles(List.of("用户", "仓库管理员"));
        process.setObjects(List.of("订单", "库存"));
        process.setConfidence(0.92);
        expected.getBusinessProcesses().add(process);
        // 对象
        BusinessObject orderObj = new BusinessObject();
        orderObj.setName("订单");
        orderObj.setDescription("用户购买商品的记录");
        orderObj.setAttributes(List.of("订单号", "商品列表", "总金额", "状态"));
        orderObj.setConfidence(0.95);
        expected.getBusinessObjects().add(orderObj);
        // 规则
        BusinessRule rule = new BusinessRule();
        rule.setName("库存验证");
        rule.setExpression("下单时，若库存不足则拒绝订单");
        rule.setConfidence(0.88);
        expected.getBusinessRules().add(rule);
        // 角色
        expected.getRoles().add("用户");
        expected.getRoles().add("仓库管理员");
        // 状态流转
        StatusTransition transition = new StatusTransition();
        transition.setBusinessObject("订单");
        transition.setFromStatus("待支付");
        transition.setToStatus("已支付");
        transition.setTrigger("用户完成支付");
        transition.setConfidence(0.90);
        expected.getStatusTransitions().add(transition);
        // 领域
        BusinessDomain domain = new BusinessDomain();
        domain.setName("电商");
        domain.setDescription("电子商务领域");
        domain.setConfidence(0.93);
        expected.getBusinessDomains().add(domain);
        // 特性
        expected.getFeatures().add("订单支付");
        expected.getFeatures().add("库存管理");

        when(llmGateway.callWithTemplate(
                eq(projectId), eq("doc-understanding"), anyMap(), eq(BusinessFactExtraction.class)))
                .thenReturn(expected);

        // 执行
        BusinessFactExtraction result = agent.extractBusinessFacts(projectId, docContent, sourcePath);

        // 验证
        assertNotNull(result);
        assertEquals(1, result.getBusinessProcesses().size());
        assertEquals("order-flow", result.getBusinessProcesses().get(0).getKey());
        assertEquals("订单处理流程", result.getBusinessProcesses().get(0).getName());
        assertEquals(4, result.getBusinessProcesses().get(0).getSteps().size());

        assertEquals(1, result.getBusinessObjects().size());
        assertEquals("订单", result.getBusinessObjects().get(0).getName());

        assertEquals(1, result.getBusinessRules().size());
        assertEquals("库存验证", result.getBusinessRules().get(0).getName());

        assertTrue(result.getRoles().contains("用户"));
        assertTrue(result.getRoles().contains("仓库管理员"));

        assertEquals(1, result.getStatusTransitions().size());
        assertEquals("订单", result.getStatusTransitions().get(0).getBusinessObject());
        assertEquals("待支付", result.getStatusTransitions().get(0).getFromStatus());

        assertEquals(1, result.getBusinessDomains().size());
        assertEquals("电商", result.getBusinessDomains().get(0).getName());

        assertEquals(2, result.getFeatures().size());
        assertTrue(result.getFeatures().contains("订单支付"));
        assertTrue(result.getFeatures().contains("库存管理"));

        // 验证 LlmGateway 被正确调用
        verify(llmGateway).callWithTemplate(
                eq(projectId), eq("doc-understanding"), anyMap(), eq(BusinessFactExtraction.class));
    }

    // ==================== 用例 2：calculateAverageConfidence — 混合实体 ====================

    @Test
    @DisplayName("包含多种业务实体时，calculateAverageConfidence 应正确计算平均置信度")
    void shouldCalculateAverageConfidenceWithMultipleEntities() {
        // 准备
        BusinessFactExtraction extraction = new BusinessFactExtraction();

        // 2 个领域：0.90 + 0.80 = 1.70
        extraction.getBusinessDomains().add(buildDomain("电商", "电商领域", 0.90));
        extraction.getBusinessDomains().add(buildDomain("物流", "物流领域", 0.80));

        // 1 个流程：0.85
        extraction.getBusinessProcesses().add(buildProcess("下单", "下单流程", 0.85));

        // 2 个对象：0.95 + 0.70 = 1.65
        extraction.getBusinessObjects().add(buildObject("订单", "订单记录", 0.95));
        extraction.getBusinessObjects().add(buildObject("商品", "商品信息", 0.70));

        // 1 条规则：0.88
        extraction.getBusinessRules().add(buildRule("库存检查", "库存不足时拒绝", 0.88));

        // 1 条状态流转：0.92
        extraction.getStatusTransitions().add(buildTransition("订单", "待支付", "已支付", "支付", 0.92));

        // 总计：1.70 + 0.85 + 1.65 + 0.88 + 0.92 = 6.00，共 7 个实体
        // 平均：6.00 / 7 ≈ 0.8571...

        // 执行
        double avg = agent.calculateAverageConfidence(extraction);

        // 验证
        assertEquals(6.00 / 7, avg, 0.0001);
    }

    // ==================== 用例 3：calculateAverageConfidence — 空结果 ====================

    @Test
    @DisplayName("无任何业务实体时，calculateAverageConfidence 应返回 0")
    void shouldReturnZeroForEmptyExtraction() {
        // 准备一个完全空的抽取结果
        BusinessFactExtraction extraction = new BusinessFactExtraction();

        // 执行
        double avg = agent.calculateAverageConfidence(extraction);

        // 验证
        assertEquals(0.0, avg, 0.0001);
    }

    // ==================== 辅助方法 ====================

    private static BusinessDomain buildDomain(String name, String description, double confidence) {
        BusinessDomain d = new BusinessDomain();
        d.setName(name);
        d.setDescription(description);
        d.setConfidence(confidence);
        return d;
    }

    private static BusinessProcess buildProcess(String key, String name, double confidence) {
        BusinessProcess p = new BusinessProcess();
        p.setKey(key);
        p.setName(name);
        p.setDescription(name + "描述");
        p.setSteps(List.of("步骤1", "步骤2"));
        p.setConfidence(confidence);
        return p;
    }

    private static BusinessObject buildObject(String name, String description, double confidence) {
        BusinessObject o = new BusinessObject();
        o.setName(name);
        o.setDescription(description);
        o.setAttributes(List.of("属性1", "属性2"));
        o.setConfidence(confidence);
        return o;
    }

    private static BusinessRule buildRule(String name, String expression, double confidence) {
        BusinessRule r = new BusinessRule();
        r.setName(name);
        r.setExpression(expression);
        r.setConfidence(confidence);
        return r;
    }

    private static StatusTransition buildTransition(String obj, String from, String to, String trigger, double confidence) {
        StatusTransition t = new StatusTransition();
        t.setBusinessObject(obj);
        t.setFromStatus(from);
        t.setToStatus(to);
        t.setTrigger(trigger);
        t.setConfidence(confidence);
        return t;
    }
}
