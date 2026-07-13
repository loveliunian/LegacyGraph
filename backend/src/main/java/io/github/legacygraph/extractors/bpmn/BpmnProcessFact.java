package io.github.legacygraph.extractors.bpmn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BPMN 流程解析中间表示 — 文件源和数据库源统一输出格式。
 * <p>
 * 由 {@link BpmnModelParser} 解析 BPMN 2.0 XML 产生,
 * 供 {@code GraphBuilder.buildBpmnProcessGraph} 构建图谱节点和边。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpmnProcessFact {
    /** 流程 key (process id) */
    private String processKey;
    /** 流程名称 */
    private String processName;
    /** 来源路径: 文件绝对路径 或 "db:{procDefId}" */
    private String sourcePath;
    /** 来源类型: FILE / DB */
    private String sourceType;
    /** 部署 ID (DB 源才有) */
    private String deploymentId;
    /** 流程版本 (DB 源才有) */
    private int version;
    /** 流程节点 */
    private List<FlowNodeFact> nodes;
    /** 流转边 (SequenceFlow) */
    private List<SequenceFlowFact> flows;
    /** 类引用 (ServiceTask/Listener 的 class/expression 引用) */
    private List<ClassRefFact> classRefs;
    /** 表达式引用 (${service.method()} 形式) */
    private List<ExprRefFact> exprRefs;

    /** 流程节点事实 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowNodeFact {
        /** BPMN 节点 id */
        private String bpmnId;
        /** 节点名称 */
        private String name;
        /** 节点类型 */
        private FlowNodeType type;
        /** 网关子类型 (Exclusive/Parallel/Inclusive), 仅 type=GATEWAY 时有效 */
        private String gatewaySubType;
        /** 描述 */
        private String description;
        /** 办理人 (UserTask) */
        private String assignee;
        /** 候选组 (UserTask) */
        private String candidateGroups;
        /** 表单 key (UserTask) */
        private String formKey;
        // ========== 运行时增强属性 (由 ProcessRuntimeAnalyzer 填充) ==========
        /** 执行次数 (运行时) */
        private long execCount;
        /** 平均时长 (毫秒, 运行时) */
        private long avgDurationMs;
        /** 驳回率 (0-1, 运行时) */
        private double rejectRate;
    }

    /** 流转边事实 (SequenceFlow) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SequenceFlowFact {
        /** BPMN 边 id */
        private String flowId;
        /** 源节点 bpmn id */
        private String sourceBpmnId;
        /** 目标节点 bpmn id */
        private String targetBpmnId;
        /** 条件表达式 (如 ${amount>10000}) */
        private String condition;
        // ========== 运行时增强属性 ==========
        /** 实际流转次数 (运行时) */
        private long flowCount;
    }

    /** 类引用事实 (ServiceTask/Listener 的 class/expression/delegateExpression) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassRefFact {
        /** 引用来源类型 */
        private ClassRefSource sourceType;
        /** 引用所在节点的 bpmn id */
        private String sourceNodeId;
        /** 全限定类名 (camunda:class / Listener class) */
        private String className;
        /** 短类名 (className 的最后一段) */
        private String shortClassName;
        /** bean 名称 (delegateExpression ${beanName}) */
        private String beanName;
        /** 原始表达式 */
        private String rawExpression;
    }

    /** 表达式引用事实 (${service.method(args)} 形式) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExprRefFact {
        /** 引用所在节点的 bpmn id */
        private String sourceNodeId;
        /** bean 名称 */
        private String beanName;
        /** 方法名称 */
        private String methodName;
        /** 原始表达式 */
        private String rawExpression;
    }

    /** 流程节点类型 */
    public enum FlowNodeType {
        USER_TASK,
        SERVICE_TASK,
        GATEWAY
    }

    /** 类引用来源类型 */
    public enum ClassRefSource {
        /** ServiceTask 的 camunda:class */
        SERVICE_TASK_CLASS,
        /** ServiceTask 的 camunda:delegateExpression */
        SERVICE_TASK_DELEGATE,
        /** ExecutionListener 的 class */
        EXECUTION_LISTENER,
        /** TaskListener 的 class */
        TASK_LISTENER
    }
}
