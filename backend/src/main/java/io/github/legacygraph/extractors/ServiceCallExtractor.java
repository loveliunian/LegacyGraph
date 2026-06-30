package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service调用关系抽取器
 * 抽取Controller -> Service, Service -> Service, Service -> Mapper的调用关系
 * 支持：字段注入(@Autowired/@Inject)、构造器注入、Lombok注入
 */
@Slf4j
public class ServiceCallExtractor {

    private final JavaParser javaParser;

    public ServiceCallExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        // 使用当前javaparser支持的最高语言级别（3.28.2 → JAVA_26），兼容 Java 8~26 所有语法
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从Java文件抽取调用关系
     * 增强：建立注入变量名→类型映射，用于精确绑定 Controller→Service、Service→Mapper 调用。
     */
    public List<CallRelation> extractFromFile(File file) throws IOException {
        List<CallRelation> relations = new ArrayList<>();
        String content = Files.readString(file.toPath());
        ParseResult<CompilationUnit> result = javaParser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java file: {}", file.getAbsolutePath());
            return relations;
        }

        CompilationUnit cu = result.getResult().get();
        String filePath = file.getAbsolutePath();
        for (var typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                String className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
                // 建立注入变量名→类型映射（字段注入 + 构造器注入）
                Map<String, String> injectedVarToType = collectInjectedVarTypes(clazz);
                Set<String> injectedServices = new HashSet<>(injectedVarToType.values());

                // 遍历所有方法，抽取方法调用
                for (MethodDeclaration method : clazz.getMethods()) {
                    String callerMethod = method.getNameAsString();
                    method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        String calledMethod = methodCall.getNameAsString();
                        CallRelation rel = new CallRelation(className, callerMethod, calledMethod);
                        rel.setSourcePath(filePath);
                        rel.setLineNumber(methodCall.getBegin().map(p -> p.line).orElse(null));

                        // 尝试解析目标：检查方法调用的 scope（如 mapper.findById → mapper 是注入的 MapperUser）
                        methodCall.getScope().ifPresent(scope -> {
                            String varName = scope.toString();
                            String resolvedType = injectedVarToType.get(varName);
                            if (resolvedType != null) {
                                rel.setTargetClass(resolvedType);
                                rel.setTargetMethod(calledMethod);
                            }
                        });
                        relations.add(rel);
                    });
                }

                // 添加注入依赖边（仅依赖关系，不含调用方法）
                for (String service : injectedServices) {
                    // 避免重复创建注入边
                    boolean alreadyAdded = relations.stream().anyMatch(r ->
                            r.getCallerClass().equals(className) && service.equals(r.getCallerMethod())
                                    && r.getTargetClass() == null);
                    if (!alreadyAdded) {
                        CallRelation depRel = new CallRelation(className, null, "injects:" + service);
                        depRel.setTargetClass(service);
                        depRel.setSourcePath(filePath);
                        relations.add(depRel);
                    }
                }
            }
        }

        return relations;
    }

    /**
     * 收集所有注入的服务依赖，返回变量名→类型名的映射
     * 支持：字段注入(@Autowired/@Inject)、构造器注入
     */
    private Map<String, String> collectInjectedVarTypes(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> varToType = new java.util.LinkedHashMap<>();

        // 处理构造器注入 — 通过解析构造参数中的类型名
        clazz.getConstructors().forEach(constructor -> {
            for (var param : constructor.getParameters()) {
                String typeName = param.getType().asString();
                if (typeName != null && !typeName.isEmpty()) {
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    varToType.put(param.getNameAsString(), typeName);
                }
            }
        });

        // 处理字段注入
        for (FieldDeclaration field : clazz.getFields()) {
            boolean hasAutowired = field.getAnnotations().stream()
                    .anyMatch(a -> {
                        String name = a.getNameAsString();
                        return "Autowired".equalsIgnoreCase(name) || "Inject".equalsIgnoreCase(name);
                    });
            if (hasAutowired) {
                for (var variable : field.getVariables()) {
                    String varName = variable.getNameAsString();
                    String typeName = variable.getType().asString();
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    varToType.put(varName, typeName);
                }
            }
        }
        return varToType;
    }

    /**
     * 调用关系
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CallRelation {
        private final String callerClass;
        private final String callerMethod;
        private final String calledMethod;
        private String targetClass;
        private String targetMethod;
        private String sourcePath;
        private Integer lineNumber;

        public CallRelation(String callerClass, String callerMethod, String calledMethod) {
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.calledMethod = calledMethod;
            this.targetClass = null;
            this.targetMethod = null;
            this.sourcePath = null;
            this.lineNumber = null;
        }
    }
}
