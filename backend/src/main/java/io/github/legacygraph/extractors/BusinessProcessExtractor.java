package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 业务流程扫描器 - 从 Service 编排方法推断 BusinessProcess 节点
 * 
 * 扫描策略：
 * - Service 类中的公开方法（非 getter/setter/CRUD 基础方法）
 * - 方法上有 @Transactional 注解（业务流程通常涉及事务）
 * - 方法名包含 process/handle/execute/submit/approve/reject 等业务动词
 * - 方法体调用多个其他方法（编排特征）
 */
@Slf4j
@Component
public class BusinessProcessExtractor {

    private static final Set<String> PROCESS_VERBS = Set.of(
            "process", "handle", "execute", "submit", "approve", "reject",
            "create", "update", "delete", "import", "export", "sync",
            "generate", "calculate", "transfer", "assign", "notify",
            "schedule", "cancel", "complete", "start", "finish"
    );

    private static final Set<String> CRUD_EXCLUDES = Set.of(
            "getById", "list", "page", "save", "insert", "remove",
            "findById", "findAll", "deleteById"
    );

    private final JavaParser javaParser;

    public BusinessProcessExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    public List<NodeExtractionResult> extract(File file) {
        List<NodeExtractionResult> results = new ArrayList<>();

        if (!file.getName().endsWith(".java")) {
            return results;
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return results;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = file.getAbsolutePath();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();

                // AST 判断：类上有 @Service / @Component 注解
                boolean isServiceClass = clazz.getAnnotations().stream()
                        .map(a -> a.getNameAsString())
                        .anyMatch(a -> "Service".equals(a) || "Component".equals(a));
                // 或继承了 Service/ServiceImpl 基类
                boolean extendsService = clazz.getExtendedTypes().stream()
                        .anyMatch(t -> t.getNameAsString().contains("Service"));

                if (!isServiceClass && !extendsService) return;

                clazz.getMethods().forEach(method -> {
                    if (isBusinessProcess(method)) {
                        String methodName = method.getNameAsString();
                        String processName = toProcessName(className, methodName);
                        String nodeKey = "process:" + (className + "." + methodName).toLowerCase();

                        boolean hasTransaction = method.getAnnotations().stream()
                                .anyMatch(a -> "Transactional".equals(a.getNameAsString()));

                        NodeExtractionResult result = NodeExtractionResult.builder()
                                .nodeType("BusinessProcess")
                                .nodeKey(nodeKey)
                                .displayName(processName)
                                .description("业务流程: " + processName + (hasTransaction ? " (事务性)" : ""))
                                .sourcePath(filePath)
                                .sourceType("CODE_AST")
                                .confidence(hasTransaction ? 0.8 : 0.65)
                                .properties(Map.of(
                                        "className", className,
                                        "methodName", methodName,
                                        "hasTransaction", hasTransaction
                                ))
                                .build();
                        results.add(result);
                    }
                });
            });
        } catch (Exception e) {
            log.warn("Failed to parse business processes from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    private boolean isBusinessProcess(MethodDeclaration method) {
        String methodName = method.getNameAsString();
        
        // 排除 getter/setter
        if (methodName.startsWith("get") || methodName.startsWith("set") 
                || methodName.startsWith("is")) {
            return false;
        }

        // 排除基础 CRUD
        if (CRUD_EXCLUDES.contains(methodName)) {
            return false;
        }

        // 必须是 public
        if (!method.isPublic()) {
            return false;
        }

        // 检查方法名是否包含业务动词
        String lowerName = methodName.toLowerCase();
        boolean hasProcessVerb = PROCESS_VERBS.stream()
                .anyMatch(verb -> lowerName.startsWith(verb) || lowerName.contains(verb));

        // 检查方法体复杂度（调用其他方法的数量）
        long methodCalls = method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).size();
        boolean isOrchestration = methodCalls >= 3;

        return hasProcessVerb || isOrchestration;
    }

    private String toProcessName(String className, String methodName) {
        // OrderService.submitOrder → 订单提交流程
        String servicePart = className.replace("ServiceImpl", "").replace("Service", "");
        String methodPart = methodName;
        
        // 驼峰转空格
        String result = servicePart + " " + methodPart;
        return result.replaceAll("([a-z])([A-Z])", "$1 $2").trim();
    }
}
