package io.github.legacygraph.agent;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.solution.TestGenerationResult;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.solution.SolutionQueryService;
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
 *
 * <p>G-18 扩展：新增 {@link #generateFromSolution(String, String, String)} 方法，
 * 基于方案（Solution）与步骤（SolutionStep）自动生成 JUnit 5 风格测试骨架，
 * 用于方案评审通过后自动触发测试生成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenerationAgent {

    private final LlmGateway llmGateway;
    private final Neo4jGraphDao neo4jGraphDao;
    /** G-18：方案触发测试生成所需的方案与步骤仓库 */
    private final SolutionQueryService solutionQueryService;

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

    // ==================== G-18：方案触发测试生成 ====================

    /**
     * 基于方案自动生成测试骨架（G-18）。
     * <p>遍历方案中所有带 {@code testDescription} 的步骤，生成 JUnit 5 风格测试骨架。
     * 不依赖 LLM 与图谱，纯结构化生成。</p>
     *
     * @param projectId     项目 ID（用于校验方案归属）
     * @param solutionId    方案 ID
     * @param testFramework 测试框架（如 JUNIT5），null/blank 时默认 JUNIT5
     * @return 测试生成结果
     */
    public TestGenerationResult generateFromSolution(String projectId,
                                                     String solutionId,
                                                     String testFramework) {
        // 1. 加载 Solution
        Solution solution = solutionQueryService.findById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }
        if (projectId != null && !projectId.isBlank()
                && solution.getProjectId() != null
                && !projectId.equals(solution.getProjectId())) {
            throw new IllegalArgumentException(
                    "方案不属于项目 " + projectId + "，实际归属: " + solution.getProjectId());
        }

        // 2. 加载 SolutionStep 列表（按 stepIndex 升序）
        List<SolutionStep> steps = solutionQueryService.findSteps(solutionId);

        // 3. 对每个有 testDescription 的 step 生成测试骨架
        String framework = (testFramework == null || testFramework.isBlank())
                ? "JUNIT5" : testFramework.toUpperCase();
        List<TestGenerationResult.GeneratedTest> generatedTests = new ArrayList<>();
        int skipped = 0;

        for (SolutionStep step : steps) {
            if (step.getTestDescription() == null || step.getTestDescription().isBlank()) {
                skipped++;
                continue;
            }
            String className = deriveClassName(step);
            String testClassName = className + "Test";
            String testCode = generateTestCode(step, framework, testClassName);
            String testFilePath = deriveTestFilePath(step, testClassName);
            String actionType = step.getActionType() != null
                    ? step.getActionType().toLowerCase() : "action";
            String testMethodName = "test_" + step.getStepIndex() + "_" + actionType;

            generatedTests.add(TestGenerationResult.GeneratedTest.builder()
                    .stepIndex(step.getStepIndex())
                    .testFilePath(testFilePath)
                    .testClassName(testClassName)
                    .testMethodName(testMethodName)
                    .testCode(testCode)
                    .build());
        }

        // 4. 汇总状态与覆盖说明
        String status;
        if (generatedTests.isEmpty()) {
            status = "NO_TESTS";
        } else if (skipped > 0) {
            status = "PARTIAL";
        } else {
            status = "SUCCESS";
        }
        String coverageNote = String.format(
                "共 %d 个步骤，生成 %d 个测试骨架，跳过 %d 个（无 testDescription）；框架=%s",
                steps.size(), generatedTests.size(), skipped, framework);

        log.info("TestGenerationAgent.generateFromSolution: solutionId={}, steps={}, generated={}, skipped={}",
                solutionId, steps.size(), generatedTests.size(), skipped);

        return TestGenerationResult.builder()
                .solutionId(solutionId)
                .generatedTests(generatedTests)
                .coverageNote(coverageNote)
                .status(status)
                .build();
    }

    /**
     * 从 SolutionStep 派生目标类名（用于构造测试类名）。
     * <p>优先级：symbolName → filePath → title → 默认。</p>
     */
    private String deriveClassName(SolutionStep step) {
        String symbolName = step.getSymbolName();
        if (symbolName != null && !symbolName.isBlank()) {
            // symbolName 可能是 "com.foo.BarService.method" 或 "BarService"
            String trimmed = symbolName.trim();
            String[] parts = trimmed.split("\\.");
            if (parts.length >= 2) {
                // 取倒数第二段作为类名（最后一段通常是方法名）
                return capitalize(parts[parts.length - 2]);
            }
            return capitalize(parts[0]);
        }
        String filePath = step.getFilePath();
        if (filePath != null && !filePath.isBlank()) {
            String fileName = filePath;
            int lastSlash = fileName.lastIndexOf('/');
            if (lastSlash >= 0) {
                fileName = fileName.substring(lastSlash + 1);
            }
            if (fileName.endsWith(".java")) {
                fileName = fileName.substring(0, fileName.length() - 5);
            }
            if (!fileName.isBlank()) {
                return capitalize(fileName);
            }
        }
        if (step.getTitle() != null && !step.getTitle().isBlank()) {
            String title = step.getTitle().replaceAll("[^a-zA-Z0-9]", "");
            if (!title.isEmpty()) {
                return capitalize(title);
            }
        }
        return "GeneratedTarget";
    }

    /**
     * 从 SolutionStep 派生测试文件路径（将 src/main/java 替换为 src/test/java）。
     */
    private String deriveTestFilePath(SolutionStep step, String testClassName) {
        String filePath = step.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return "src/test/java/" + testClassName + ".java";
        }
        String testPath = filePath;
        // 将 /main/ 替换为 /test/
        if (testPath.contains("/main/")) {
            testPath = testPath.replace("/main/", "/test/");
        }
        // 替换文件名为测试类名
        int lastSlash = testPath.lastIndexOf('/');
        if (lastSlash >= 0) {
            testPath = testPath.substring(0, lastSlash + 1) + testClassName + ".java";
        } else {
            testPath = "src/test/java/" + testClassName + ".java";
        }
        return testPath;
    }

    /**
     * 基于 SolutionStep 信息生成 JUnit 5 风格测试骨架代码。
     * <p>包含 {@code @DisplayName}、{@code @BeforeEach}、{@code @Test} 注解，
     * 并嵌入 step 的 testDescription 与 codeSnippet（如有）作为引导注释。</p>
     *
     * @param step           方案步骤
     * @param testFramework  测试框架（当前仅支持 JUNIT5，其他值会回落到 JUnit 5 风格）
     * @param testClassName  测试类名（{ClassName}Test）
     * @return 测试代码字符串
     */
    private String generateTestCode(SolutionStep step, String testFramework, String testClassName) {
        StringBuilder sb = new StringBuilder();

        // 1. 包导入
        sb.append("import org.junit.jupiter.api.BeforeEach;\n");
        sb.append("import org.junit.jupiter.api.DisplayName;\n");
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");

        // 2. 类级 Javadoc 与 @DisplayName
        String title = step.getTitle() != null ? step.getTitle() : "步骤 " + step.getStepIndex();
        sb.append("/**\n");
        sb.append(" * ").append(testClassName).append(" — 自动生成测试骨架（G-18）。\n");
        sb.append(" * <p>基于 SolutionStep[").append(step.getStepIndex()).append("] 生成；\n");
        sb.append(" * 动作类型: ").append(step.getActionType() != null ? step.getActionType() : "UNKNOWN").append("，\n");
        sb.append(" * 框架: ").append(testFramework).append("。</p>\n");
        sb.append(" */\n");
        sb.append("@DisplayName(\"").append(escapeForJavaString(title)).append("\")\n");
        sb.append("class ").append(testClassName).append(" {\n\n");

        // 3. 被测对象字段（注释形式，避免编译错误）
        String targetType = deriveClassName(step);
        sb.append("    // 被测对象（具体初始化取决于业务实现）\n");
        sb.append("    // private ").append(targetType).append(" target;\n\n");

        // 4. @BeforeEach
        sb.append("    @BeforeEach\n");
        sb.append("    void setUp() {\n");
        sb.append("        // TODO: 初始化被测对象与依赖\n");
        if (step.getCodeSnippet() != null && !step.getCodeSnippet().isBlank()) {
            // 嵌入 codeSnippet 作为参考注释
            sb.append("        // 参考实现代码片段：\n");
            String snippet = step.getCodeSnippet();
            for (String line : snippet.split("\n", -1)) {
                sb.append("        // ").append(line).append("\n");
            }
        } else {
            sb.append("        // target = new ").append(targetType).append("();\n");
        }
        sb.append("    }\n\n");

        // 5. 主测试方法 test_{stepIndex}_{actionType}
        String actionType = step.getActionType() != null
                ? step.getActionType().toLowerCase() : "action";
        String testMethodName = "test_" + step.getStepIndex() + "_" + actionType;
        sb.append("    @Test\n");
        sb.append("    @DisplayName(\"").append(testMethodName).append("\")\n");
        sb.append("    void ").append(testMethodName).append("() {\n");
        if (step.getTestDescription() != null && !step.getTestDescription().isBlank()) {
            sb.append("        // 测试描述：")
                    .append(escapeForJavaString(step.getTestDescription())).append("\n");
        }
        sb.append("        // TODO: 实现具体断言\n");
        sb.append("        // 1. 准备测试数据\n");
        sb.append("        // 2. 调用被测方法\n");
        sb.append("        // 3. 验证结果（示例：assertEquals(expected, actual)）\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    /** 首字母大写 */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 转义字符串字面量中的特殊字符（用于 @DisplayName 等） */
    private String escapeForJavaString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
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
