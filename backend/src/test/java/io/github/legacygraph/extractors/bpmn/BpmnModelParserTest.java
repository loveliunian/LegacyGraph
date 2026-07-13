package io.github.legacygraph.extractors.bpmn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BpmnModelParser 单元测试。
 * 验证 BPMN 2.0 XML 解析:节点提取、流转边、类引用、表达式引用。
 */
class BpmnModelParserTest {

    @TempDir
    Path tempDir;

    private final BpmnModelParser parser = new BpmnModelParser();

    /**
     * 最小化 BPMN 2.0 XML,包含:
     * - 1 个 UserTask (经理审批)
     * - 1 个 ServiceTask (camunda:expression="${leaveService.approve(task)}")
     * - 1 个 ExclusiveGateway (条件 ${days>3})
     * - 若干 SequenceFlow
     */
    private static final String BPMN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                         targetNamespace="http://test">
              <process id="leaveProcess" name="请假流程" isExecutable="true">
                <startEvent id="start" name="开始"/>
                <userTask id="managerApprove" name="经理审批" camunda:assignee="${manager}"
                          camunda:candidateGroups="managers" camunda:formKey="approve.form"/>
                <serviceTask id="autoApprove" name="自动审批"
                             camunda:expression="${leaveService.approve(task)}"/>
                <exclusiveGateway id="daysCheck" name="天数判断"/>
                <endEvent id="end" name="结束"/>
                <sequenceFlow id="flow1" sourceRef="start" targetRef="managerApprove"/>
                <sequenceFlow id="flow2" sourceRef="managerApprove" targetRef="daysCheck"/>
                <sequenceFlow id="flow3" sourceRef="daysCheck" targetRef="autoApprove">
                  <conditionExpression>${days &gt; 3}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="flow4" sourceRef="daysCheck" targetRef="end">
                  <conditionExpression>${days &lt;= 3}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="flow5" sourceRef="autoApprove" targetRef="end"/>
              </process>
            </definitions>
            """;

    @Test
    void parseFromFile_extractsProcessKeyAndName() throws Exception {
        File bpmnFile = writeBpmnFile("leave.bpmn");

        BpmnProcessFact fact = parser.parseFromFile(bpmnFile);

        assertNotNull(fact);
        assertEquals("leaveProcess", fact.getProcessKey());
        assertEquals("请假流程", fact.getProcessName());
        assertEquals("FILE", fact.getSourceType());
    }

    @Test
    void parseFromFile_extractsUserTask() throws Exception {
        File bpmnFile = writeBpmnFile("leave.bpmn");

        BpmnProcessFact fact = parser.parseFromFile(bpmnFile);

        assertNotNull(fact);
        // UserTask: managerApprove
        var userTask = fact.getNodes().stream()
                .filter(n -> n.getType() == BpmnProcessFact.FlowNodeType.USER_TASK)
                .findFirst().orElse(null);
        assertNotNull(userTask);
        assertEquals("managerApprove", userTask.getBpmnId());
        assertEquals("经理审批", userTask.getName());
        assertEquals("${manager}", userTask.getAssignee());
        assertEquals("managers", userTask.getCandidateGroups());
        assertEquals("approve.form", userTask.getFormKey());
    }

    @Test
    void parseFromFile_extractsServiceTaskAndExpression() throws Exception {
        File bpmnFile = writeBpmnFile("leave.bpmn");

        BpmnProcessFact fact = parser.parseFromFile(bpmnFile);

        assertNotNull(fact);
        // ServiceTask: autoApprove
        var serviceTask = fact.getNodes().stream()
                .filter(n -> n.getType() == BpmnProcessFact.FlowNodeType.SERVICE_TASK)
                .findFirst().orElse(null);
        assertNotNull(serviceTask);
        assertEquals("autoApprove", serviceTask.getBpmnId());

        // 表达式引用 ${leaveService.approve(task)}
        assertFalse(fact.getExprRefs().isEmpty());
        var exprRef = fact.getExprRefs().get(0);
        assertEquals("leaveService", exprRef.getBeanName());
        assertEquals("approve", exprRef.getMethodName());
        assertEquals("autoApprove", exprRef.getSourceNodeId());
    }

    @Test
    void parseFromFile_extractsGatewayWithSubType() throws Exception {
        File bpmnFile = writeBpmnFile("leave.bpmn");

        BpmnProcessFact fact = parser.parseFromFile(bpmnFile);

        assertNotNull(fact);
        var gateway = fact.getNodes().stream()
                .filter(n -> n.getType() == BpmnProcessFact.FlowNodeType.GATEWAY)
                .findFirst().orElse(null);
        assertNotNull(gateway);
        assertEquals("daysCheck", gateway.getBpmnId());
        assertEquals("Exclusive", gateway.getGatewaySubType());
    }

    @Test
    void parseFromFile_extractsSequenceFlowsWithConditions() throws Exception {
        File bpmnFile = writeBpmnFile("leave.bpmn");

        BpmnProcessFact fact = parser.parseFromFile(bpmnFile);

        assertNotNull(fact);
        // 5 条 SequenceFlow
        assertEquals(5, fact.getFlows().size());

        // flow3 有条件 ${days > 3}
        var flow3 = fact.getFlows().stream()
                .filter(f -> "flow3".equals(f.getFlowId()))
                .findFirst().orElse(null);
        assertNotNull(flow3);
        assertEquals("daysCheck", flow3.getSourceBpmnId());
        assertEquals("autoApprove", flow3.getTargetBpmnId());
        assertNotNull(flow3.getCondition());
        assertTrue(flow3.getCondition().contains("days"));
    }

    @Test
    void parseFromFile_returnsNullForInvalidBpmn() throws Exception {
        File invalidFile = tempDir.resolve("invalid.bpmn").toFile();
        Files.writeString(invalidFile.toPath(), "<not-bpmn>invalid</not-bpmn>");

        BpmnProcessFact fact = parser.parseFromFile(invalidFile);

        assertNull(fact);
    }

    @Test
    void parseFromFile_handlesCamundaClassAttribute() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                             targetNamespace="http://test">
                  <process id="auditProcess" name="审计流程" isExecutable="true">
                    <startEvent id="start"/>
                    <serviceTask id="audit" name="执行审计"
                                 camunda:class="com.example.AuditDelegate"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="audit"/>
                    <sequenceFlow id="f2" sourceRef="audit" targetRef="end"/>
                  </process>
                </definitions>
                """;
        File file = tempDir.resolve("audit.bpmn").toFile();
        Files.writeString(file.toPath(), xml);

        BpmnProcessFact fact = parser.parseFromFile(file);

        assertNotNull(fact);
        assertFalse(fact.getClassRefs().isEmpty());
        var classRef = fact.getClassRefs().get(0);
        assertEquals("com.example.AuditDelegate", classRef.getClassName());
        assertEquals("AuditDelegate", classRef.getShortClassName());
        assertEquals("audit", classRef.getSourceNodeId());
        assertEquals(BpmnProcessFact.ClassRefSource.SERVICE_TASK_CLASS, classRef.getSourceType());
    }

    private File writeBpmnFile(String filename) throws Exception {
        File file = tempDir.resolve(filename).toFile();
        Files.writeString(file.toPath(), BPMN_XML);
        return file;
    }
}
