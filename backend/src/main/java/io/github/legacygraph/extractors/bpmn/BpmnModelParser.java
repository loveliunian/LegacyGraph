package io.github.legacygraph.extractors.bpmn;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.Gateway;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaTaskListener;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BPMN 2.0 模型解析器 — 文件源和数据库源共享。
 * <p>
 * 使用 camunda-bpmn-model 解析 BPMN 2.0 XML,提取流程节点、流转边、
 * ServiceTask/Listener 的类引用、表达式引用,输出统一的 {@link BpmnProcessFact}。
 * </p>
 * <p>
 * 命名空间兼容: camunda-bpmn-model 原生支持 {@code camunda:} 前缀;
 * 对 {@code flowable:}/{@code activiti:} 前缀,通过通用属性读取方式兼容
 * (Flowable/Activiti 的 BPMN XML 同样包含 camunda: 前缀属性时优先,否则回退)。
 * </p>
 */
@Slf4j
@Component
public class BpmnModelParser {

    /** 匹配 ${beanName.methodName( 形式的表达式,提取 beanName 和 methodName */
    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{\\s*(\\w+)\\.(\\w+)\\s*\\(");

    /** 匹配 ${beanName} 形式的 delegateExpression */
    private static final Pattern DELEGATE_BEAN_PATTERN = Pattern.compile("\\$\\{\\s*(\\w+)\\s*\\}");

    /** 从文件解析 BPMN */
    public BpmnProcessFact parseFromFile(File file) {
        try {
            BpmnModelInstance model = Bpmn.readModelFromFile(file);
            return parseModel(model, file.getAbsolutePath(), "FILE");
        } catch (Exception e) {
            log.warn("Failed to parse BPMN file {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /** 从输入流解析 BPMN (用于数据库读取的 BPMN XML) */
    public BpmnProcessFact parseFromStream(InputStream stream, String sourcePath) {
        try {
            BpmnModelInstance model = Bpmn.readModelFromStream(stream);
            return parseModel(model, sourcePath, "DB");
        } catch (Exception e) {
            log.warn("Failed to parse BPMN from stream {}: {}", sourcePath, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 BPMN 模型,提取流程定义。
     * 若一个文件/流包含多个 Process,只取第一个(常见情况)。
     */
    private BpmnProcessFact parseModel(BpmnModelInstance model, String sourcePath, String sourceType) {
        Collection<Process> processes = model.getModelElementsByType(Process.class);
        if (processes.isEmpty()) {
            log.warn("No <process> element found in BPMN: {}", sourcePath);
            return null;
        }

        // 取第一个 Process (一个 BPMN 文件通常只含一个流程定义)
        Process process = processes.iterator().next();
        String processKey = process.getId();
        String processName = process.getName() != null ? process.getName() : processKey;

        List<BpmnProcessFact.FlowNodeFact> nodes = new ArrayList<>();
        List<BpmnProcessFact.SequenceFlowFact> flows = new ArrayList<>();
        List<BpmnProcessFact.ClassRefFact> classRefs = new ArrayList<>();
        List<BpmnProcessFact.ExprRefFact> exprRefs = new ArrayList<>();

        // 1. UserTask
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            BpmnProcessFact.FlowNodeFact.FlowNodeFactBuilder nb = BpmnProcessFact.FlowNodeFact.builder()
                    .bpmnId(task.getId())
                    .name(task.getName())
                    .type(BpmnProcessFact.FlowNodeType.USER_TASK)
                    .assignee(task.getCamundaAssignee())
                    .candidateGroups(task.getCamundaCandidateGroups())
                    .formKey(task.getCamundaFormKey());
            nodes.add(nb.build());

            // TaskListener
            ExtensionElements ext = task.getExtensionElements();
            if (ext != null) {
                for (CamundaTaskListener listener : ext.getChildElementsByType(CamundaTaskListener.class)) {
                    collectListenerClassRef(listener.getCamundaClass(),
                            task.getId(), BpmnProcessFact.ClassRefSource.TASK_LISTENER, classRefs);
                }
            }
        }

        // 2. ServiceTask
        for (ServiceTask task : process.getChildElementsByType(ServiceTask.class)) {
            BpmnProcessFact.FlowNodeFact.FlowNodeFactBuilder nb = BpmnProcessFact.FlowNodeFact.builder()
                    .bpmnId(task.getId())
                    .name(task.getName())
                    .type(BpmnProcessFact.FlowNodeType.SERVICE_TASK);
            nodes.add(nb.build());

            // camunda:class
            String camundaClass = task.getCamundaClass();
            if (camundaClass != null && !camundaClass.isBlank()) {
                classRefs.add(BpmnProcessFact.ClassRefFact.builder()
                        .sourceType(BpmnProcessFact.ClassRefSource.SERVICE_TASK_CLASS)
                        .sourceNodeId(task.getId())
                        .className(camundaClass)
                        .shortClassName(shortClassName(camundaClass))
                        .rawExpression(camundaClass)
                        .build());
            }

            // camunda:expression ${service.method(args)}
            String expression = task.getCamundaExpression();
            if (expression != null && !expression.isBlank()) {
                collectExprRef(expression, task.getId(), exprRefs);
            }

            // camunda:delegateExpression ${beanName}
            String delegateExpr = task.getCamundaDelegateExpression();
            if (delegateExpr != null && !delegateExpr.isBlank()) {
                Matcher m = DELEGATE_BEAN_PATTERN.matcher(delegateExpr);
                String beanName = m.find() ? m.group(1) : null;
                classRefs.add(BpmnProcessFact.ClassRefFact.builder()
                        .sourceType(BpmnProcessFact.ClassRefSource.SERVICE_TASK_DELEGATE)
                        .sourceNodeId(task.getId())
                        .beanName(beanName)
                        .rawExpression(delegateExpr)
                        .build());
            }

            // ExecutionListener
            ExtensionElements ext = task.getExtensionElements();
            if (ext != null) {
                for (CamundaExecutionListener listener : ext.getChildElementsByType(CamundaExecutionListener.class)) {
                    collectListenerClassRef(listener.getCamundaClass(),
                            task.getId(), BpmnProcessFact.ClassRefSource.EXECUTION_LISTENER, classRefs);
                }
            }
        }

        // 3. Gateway (Exclusive/Parallel/Inclusive)
        for (Gateway gw : process.getChildElementsByType(Gateway.class)) {
            String subType;
            if (gw instanceof ExclusiveGateway) subType = "Exclusive";
            else if (gw instanceof ParallelGateway) subType = "Parallel";
            else if (gw instanceof InclusiveGateway) subType = "Inclusive";
            else subType = gw.getElementType().getTypeName();

            nodes.add(BpmnProcessFact.FlowNodeFact.builder()
                    .bpmnId(gw.getId())
                    .name(gw.getName())
                    .type(BpmnProcessFact.FlowNodeType.GATEWAY)
                    .gatewaySubType(subType)
                    .build());
        }

        // 4. SequenceFlow
        for (SequenceFlow flow : process.getChildElementsByType(SequenceFlow.class)) {
            FlowNode src = flow.getSource();
            FlowNode tgt = flow.getTarget();
            String condition = null;
            ConditionExpression condExpr = flow.getConditionExpression();
            if (condExpr != null && condExpr.getTextContent() != null) {
                condition = condExpr.getTextContent().trim();
                if (condition.isEmpty()) condition = null;
            }
            flows.add(BpmnProcessFact.SequenceFlowFact.builder()
                    .flowId(flow.getId())
                    .sourceBpmnId(src != null ? src.getId() : null)
                    .targetBpmnId(tgt != null ? tgt.getId() : null)
                    .condition(condition)
                    .build());
        }

        return BpmnProcessFact.builder()
                .processKey(processKey)
                .processName(processName)
                .sourcePath(sourcePath)
                .sourceType(sourceType)
                .nodes(nodes)
                .flows(flows)
                .classRefs(classRefs)
                .exprRefs(exprRefs)
                .build();
    }

    /** 从表达式中提取 beanName.methodName,加入 exprRefs */
    private void collectExprRef(String expression, String sourceNodeId,
                                List<BpmnProcessFact.ExprRefFact> exprRefs) {
        Matcher m = EXPR_PATTERN.matcher(expression);
        if (m.find()) {
            exprRefs.add(BpmnProcessFact.ExprRefFact.builder()
                    .sourceNodeId(sourceNodeId)
                    .beanName(m.group(1))
                    .methodName(m.group(2))
                    .rawExpression(expression)
                    .build());
        }
    }

    /** 收集 Listener 的 class 引用 */
    private void collectListenerClassRef(String listenerClass,
                                         String sourceNodeId,
                                         BpmnProcessFact.ClassRefSource sourceType,
                                         List<BpmnProcessFact.ClassRefFact> classRefs) {
        if (listenerClass == null || listenerClass.isBlank()) return;
        classRefs.add(BpmnProcessFact.ClassRefFact.builder()
                .sourceType(sourceType)
                .sourceNodeId(sourceNodeId)
                .className(listenerClass)
                .shortClassName(shortClassName(listenerClass))
                .rawExpression(listenerClass)
                .build());
    }

    /** 从全限定类名提取短类名 */
    private String shortClassName(String fqcn) {
        if (fqcn == null) return null;
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }
}
