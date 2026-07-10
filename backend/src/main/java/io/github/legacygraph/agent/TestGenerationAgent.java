package io.github.legacygraph.agent;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试生成 Agent（v6.0 P11：TEST_GENERATION）— 根据图谱上下文为目标方法生成 JUnit 5 单元测试代码。
 * <p>工作流程：
 * <ol>
 *   <li>从 Method 节点 properties 获取方法签名和参数类型</li>
 *   <li>从 CALLS 边获取依赖列表（确定需 mock 的对象）</li>
 *   <li>从 ApiEndpoint.properties 获取契约信息（契约测试场景）</li>
 *   <li>调用 LLM（test-generation 模板）生成 JUnit 测试代码</li>
 * </ol>
 * 输出为测试代码字符串（@ExtendWith(MockitoExtension.class) + @Mock + @InjectMocks）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenerationAgent {

    private final LlmGateway llmGateway;
    private final Neo4jGraphDao neo4jGraphDao;

    /**
     * 为目标方法生成测试代码（AgentEnvelope 合约版本）。
     *
     * @param envelope 包含 TestGenerationInput 的信封
     * @return 生成的 JUnit 测试代码字符串
     */
    public String generate(AgentEnvelope<TestGenerationInput> envelope) {
        TestGenerationInput input = envelope.getInput();
        if (input == null) {
            log.warn("TestGenerationAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        return generate(envelope.getProjectId(), input);
    }

    /**
     * 为目标方法生成测试代码。
     * <p>从图谱查询方法签名、依赖列表和契约信息，构建 prompt 变量后调用 LLM。</p>
     *
     * @param projectId 项目 ID
     * @param input     测试生成输入（方法标识 + 可选上下文）
     * @return 生成的 JUnit 测试代码字符串
     */
    public String generate(String projectId, TestGenerationInput input) {
        if (input == null || projectId == null) {
            return null;
        }

        // 1. 查询目标 Method 节点
        GraphNode methodNode = resolveMethodNode(projectId, input);
        if (methodNode == null) {
            log.warn("TestGenerationAgent: method node not found for {}", input.getMethodNodeKey());
            return "// 未找到目标方法: " + input.getMethodNodeKey();
        }

        // 2. 从图谱收集上下文（方法签名、依赖列表、契约信息）
        String methodSignature = extractMethodSignature(methodNode, input);
        String className = extractClassName(methodNode, input);
        String dependencies = collectDependencies(projectId, input, methodNode);
        String contractInfo = collectContractInfo(projectId, input, methodNode);
        String existingTests = input.getExistingTests() != null ? input.getExistingTests() : "（无）";

        // 3. 构建 prompt 变量
        Map<String, String> variables = new HashMap<>();
        variables.put("className", className);
        variables.put("methodSignature", methodSignature);
        variables.put("methodName", methodNode.getNodeName() != null ? methodNode.getNodeName() : "");
        variables.put("dependencies", dependencies);
        variables.put("contractInfo", contractInfo);
        variables.put("existingTests", existingTests);

        // 4. 调用 LLM 生成测试代码（String 响应类型 → 直接返回文本）
        return llmGateway.callWithTemplate(projectId, "test-generation",
                variables, String.class);
    }

    /**
     * 解析目标 Method 节点 — 先按 nodeKey 查找，再按 nodeId 查找。
     */
    private GraphNode resolveMethodNode(String projectId, TestGenerationInput input) {
        String nodeKey = input.getMethodNodeKey();
        String versionId = input.getVersionId();
        if (nodeKey == null || nodeKey.isBlank()) {
            return null;
        }
        // 先按 nodeKey 查找
        GraphNode node = neo4jGraphDao.findNode(projectId, versionId, NodeType.Method.name(), nodeKey).orElse(null);
        if (node != null) {
            return node;
        }
        // 回退：按 nodeId 查找
        return neo4jGraphDao.findNodeById(nodeKey).orElse(null);
    }

    /**
     * 提取方法签名 — 优先用 input 中的，回退到从 nodeKey 解析。
     */
    private String extractMethodSignature(GraphNode methodNode, TestGenerationInput input) {
        if (input.getMethodSignature() != null && !input.getMethodSignature().isBlank()) {
            return input.getMethodSignature();
        }
        // 从 nodeKey 解析（格式：qualifiedClassName.methodSignature）
        String nodeKey = methodNode.getNodeKey();
        if (nodeKey != null) {
            int lastDot = nodeKey.lastIndexOf('.');
            if (lastDot > 0 && lastDot < nodeKey.length() - 1) {
                return nodeKey.substring(lastDot + 1);
            }
        }
        return methodNode.getNodeName() != null ? methodNode.getNodeName() + "()" : "unknown()";
    }

    /**
     * 提取类名 — 优先用 input 中的，回退到从 nodeKey 或 className 属性解析。
     */
    private String extractClassName(GraphNode methodNode, TestGenerationInput input) {
        if (input.getClassName() != null && !input.getClassName().isBlank()) {
            return input.getClassName();
        }
        if (methodNode.getClassName() != null && !methodNode.getClassName().isBlank()) {
            return methodNode.getClassName();
        }
        // 从 nodeKey 解析（格式：qualifiedClassName.methodSignature）
        String nodeKey = methodNode.getNodeKey();
        if (nodeKey != null) {
            int lastDot = nodeKey.lastIndexOf('.');
            if (lastDot > 0) {
                return nodeKey.substring(0, lastDot);
            }
        }
        return methodNode.getNodeName() != null ? methodNode.getNodeName() : "UnknownClass";
    }

    /**
     * 收集依赖列表 — 从 CALLS 边查询目标方法的下游调用（确定需 mock 的对象）。
     * <p>查询 Method 节点的 outgoing CALLS 边，提取被调用方法的所属类作为依赖。</p>
     *
     * @return 依赖列表文本（每行一个依赖类），无依赖时返回 "（无）"
     */
    private String collectDependencies(String projectId, TestGenerationInput input, GraphNode methodNode) {
        // 如果调用方已提供依赖列表，直接使用
        if (input.getDependencies() != null && !input.getDependencies().isBlank()) {
            return input.getDependencies();
        }
        // 从图谱查询 CALLS 边
        List<GraphEdge> callsEdges = neo4jGraphDao.queryEdges(
                projectId, input.getVersionId(),
                EdgeType.CALLS.name(), null, methodNode.getId(),
                null, null, 50);
        if (callsEdges == null || callsEdges.isEmpty()) {
            return "（无）";
        }
        // 提取被调用方法所属类作为依赖（去重）
        List<String> dependencies = new ArrayList<>();
        for (GraphEdge edge : callsEdges) {
            // outgoing 边：当前节点是 from
            if (!methodNode.getId().equals(edge.getFromNodeId())) {
                continue;
            }
            GraphNode callee = neo4jGraphDao.findNodeById(edge.getToNodeId()).orElse(null);
            if (callee == null) {
                continue;
            }
            String depClassName = callee.getClassName();
            if (depClassName == null || depClassName.isBlank()) {
                // 回退到从 callee 的 nodeKey 提取类名
                String calleeKey = callee.getNodeKey();
                if (calleeKey != null) {
                    int lastDot = calleeKey.lastIndexOf('.');
                    if (lastDot > 0) {
                        depClassName = calleeKey.substring(0, lastDot);
                    }
                }
            }
            if (depClassName != null && !depClassName.isBlank() && !dependencies.contains(depClassName)) {
                String simpleName = depClassName.contains(".")
                        ? depClassName.substring(depClassName.lastIndexOf('.') + 1)
                        : depClassName;
                dependencies.add(simpleName + " (" + callee.getNodeName() + ")");
            }
        }
        if (dependencies.isEmpty()) {
            return "（无）";
        }
        return dependencies.stream().map(d -> "- " + d).collect(Collectors.joining("\n"));
    }

    /**
     * 收集契约信息 — 查询与目标方法关联的 ApiEndpoint 节点的 properties。
     * <p>通过 HANDLED_BY 边反查 ApiEndpoint，提取其 properties 中的契约信息
     * （HTTP 方法、路径、参数、响应等）。</p>
     *
     * @return 契约信息文本，无契约时返回 "（无）"
     */
    private String collectContractInfo(String projectId, TestGenerationInput input, GraphNode methodNode) {
        // 如果调用方已提供契约信息，直接使用
        if (input.getContractInfo() != null && !input.getContractInfo().isBlank()) {
            return input.getContractInfo();
        }
        // 通过 HANDLED_BY 边反查 ApiEndpoint
        List<GraphEdge> handledByEdges = neo4jGraphDao.queryEdges(
                projectId, input.getVersionId(),
                EdgeType.HANDLED_BY.name(), methodNode.getId(), null,
                null, null, 10);
        if (handledByEdges == null || handledByEdges.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (GraphEdge edge : handledByEdges) {
            // HANDLED_BY: ApiEndpoint --HANDLED_BY--> Method
            // 当前方法是 to，ApiEndpoint 是 from
            if (!methodNode.getId().equals(edge.getToNodeId())) {
                continue;
            }
            GraphNode apiNode = neo4jGraphDao.findNodeById(edge.getFromNodeId()).orElse(null);
            if (apiNode == null || !NodeType.ApiEndpoint.name().equals(apiNode.getNodeType())) {
                continue;
            }
            sb.append("- API: ").append(apiNode.getDisplayName());
            if (apiNode.getProperties() != null && !apiNode.getProperties().isBlank()) {
                sb.append(" (properties: ").append(apiNode.getProperties()).append(")");
            }
            sb.append("\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : "（无）";
    }

    /**
     * 测试生成输入 DTO。
     */
    @lombok.Data
    @lombok.Builder
    public static class TestGenerationInput {
        /** 目标 Method 节点 key（或 nodeId） */
        private String methodNodeKey;
        /** 版本 ID */
        private String versionId;
        /** 方法签名（可选，不填则从图谱提取） */
        private String methodSignature;
        /** 类全限定名（可选，不填则从图谱提取） */
        private String className;
        /** 依赖列表（可选，不填则从 CALLS 边查询） */
        private String dependencies;
        /** 契约信息（可选，不填则从 ApiEndpoint 查询） */
        private String contractInfo;
        /** 现有测试（避免重复生成） */
        private String existingTests;
    }
}
