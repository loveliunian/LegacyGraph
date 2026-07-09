package io.github.legacygraph.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Code Property Graph（CPG）基础构建器。
 * <p>
 * P4a 基础框架：整合 {@link ControlFlowGraph} + {@link DataFlowGraph}，
 * 给定文件路径 / 类名 / 方法名，返回方法内的 CFG 节点 + DFG 边。
 * <p>
 * 设计边界：
 * <ul>
 *   <li>仅方法内分析，不做跨过程。</li>
 *   <li>不写 Neo4j，只返回内存对象 {@link CpgResult}。</li>
 *   <li>{@code className} 可传简单名或 FQN 后缀；为空时取文件中第一个类。</li>
 *   <li>{@code methodName} 匹配简单名；若有重载，取第一个。</li>
 * </ul>
 */
@Slf4j
@Component
public class CpgBuilder {

    /** ThreadLocal JavaParser —— JavaParser 非线程安全 */
    private final ThreadLocal<JavaParser> javaParser = ThreadLocal.withInitial(() -> {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        return new JavaParser(config);
    });

    private final ControlFlowGraph cfgBuilder = new ControlFlowGraph();
    private final DataFlowGraph dfgBuilder = new DataFlowGraph();

    /**
     * 构建指定方法的 CPG 结果。
     *
     * @param filePath  Java 源文件路径
     * @param className 类名（简单名或 FQN 后缀；null/空表示取第一个类）
     * @param methodName 方法名（简单名）
     * @return CPG 结果（CFG 节点 + DFG 边）
     */
    public CpgResult build(String filePath, String className, String methodName) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }

        CompilationUnit cu = parse(path);
        ClassOrInterfaceDeclaration clazz = findClass(cu, className);
        if (clazz == null) {
            throw new IllegalStateException("Class not found: " + className + " in " + filePath);
        }
        MethodDeclaration method = findMethod(clazz, methodName);
        if (method == null) {
            throw new IllegalStateException("Method not found: " + methodName + " in class " + clazz.getNameAsString());
        }

        // 1) CFG
        ControlFlowGraph.CFGNode entry = cfgBuilder.build(method);
        List<ControlFlowGraph.CFGNode> allNodes = ControlFlowGraph.collectAll(entry);

        // 2) 构造变量名 → 类型名映射（参数 + 局部变量），用于 DFG 元信息
        Map<String, String> varToType = collectVarToType(method);

        // 3) DFG
        List<DataFlowGraph.DataFlowEdge> edges = dfgBuilder.analyze(method, varToType);

        // 4) 组装可序列化结果
        List<CpgNodeView> nodeViews = new ArrayList<>(allNodes.size());
        for (ControlFlowGraph.CFGNode n : allNodes) {
            nodeViews.add(toView(n));
        }
        return new CpgResult(
                filePath,
                clazz.getFullyQualifiedName().orElse(clazz.getNameAsString()),
                method.getNameAsString(),
                nodeViews,
                edges
        );
    }

    private CompilationUnit parse(Path javaFile) throws IOException {
        JavaParser parser = javaParser.get();
        ParseResult<CompilationUnit> result = parser.parse(Files.readString(javaFile));
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IOException("Failed to parse Java file: " + javaFile
                    + " (problems=" + result.getProblems().size() + ")");
        }
        return result.getResult().get();
    }

    private ClassOrInterfaceDeclaration findClass(CompilationUnit cu, String className) {
        List<ClassOrInterfaceDeclaration> all = cu.findAll(ClassOrInterfaceDeclaration.class);
        if (all.isEmpty()) {
            return null;
        }
        if (className == null || className.isBlank()) {
            return all.get(0);
        }
        // 支持简单名 / FQN 后缀匹配
        String normalized = className.trim();
        for (ClassOrInterfaceDeclaration c : all) {
            String fqn = c.getFullyQualifiedName().orElse(c.getNameAsString());
            if (fqn.equals(normalized) || c.getNameAsString().equals(normalized)
                    || fqn.endsWith("." + normalized)) {
                return c;
            }
        }
        return null;
    }

    private MethodDeclaration findMethod(ClassOrInterfaceDeclaration clazz, String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        for (MethodDeclaration m : clazz.getMethods()) {
            if (methodName.equals(m.getNameAsString())) {
                return m;
            }
        }
        return null;
    }

    private Map<String, String> collectVarToType(MethodDeclaration method) {
        Map<String, String> map = new HashMap<>();
        // 参数
        method.getParameters().forEach(p -> map.put(p.getNameAsString(), p.getType().asString()));
        // 方法体内局部变量声明
        method.getBody().ifPresent(body ->
                body.findAll(VariableDeclarationExpr.class).forEach(vde ->
                        vde.getVariables().forEach((VariableDeclarator v) ->
                                map.put(v.getNameAsString(), v.getType().asString()))));
        return map;
    }

    private CpgNodeView toView(ControlFlowGraph.CFGNode node) {
        CpgNodeView view = new CpgNodeView();
        view.setId(node.getId());
        view.setNodeType(node.getNodeType().name());
        List<String> texts = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        for (Statement s : node.getStatements()) {
            texts.add(safeText(s));
            lines.add(s.getBegin().map(b -> b.line).orElse(0));
        }
        view.setStatementTexts(texts);
        view.setLineNumbers(lines);
        List<Integer> succIds = new ArrayList<>(node.getSuccessors().size());
        for (ControlFlowGraph.CFGNode s : node.getSuccessors()) {
            succIds.add(s.getId());
        }
        view.setSuccessorIds(succIds);
        return view;
    }

    private String safeText(Statement s) {
        try {
            String txt = s.toString();
            // 截断过长文本，避免单节点 JSON 过大
            return txt.length() > 240 ? txt.substring(0, 240) + "..." : txt;
        } catch (Exception e) {
            return "<unprintable>";
        }
    }

    // ---------- 结果 DTO ----------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpgResult {
        private String filePath;
        private String className;
        private String methodName;
        private List<CpgNodeView> cfgNodes;
        private List<DataFlowGraph.DataFlowEdge> dfgEdges;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpgNodeView {
        private int id;
        private String nodeType;
        /** 节点承载语句的源码文本（每条一行） */
        private List<String> statementTexts;
        /** 与 statementTexts 对应的起始行号 */
        private List<Integer> lineNumbers;
        /** 后继节点 id 列表 */
        private List<Integer> successorIds;
    }
}
