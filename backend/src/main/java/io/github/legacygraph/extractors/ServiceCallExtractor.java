package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
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
        this.javaParser = new JavaParser();
    }

    /**
     * 从Java文件抽取调用关系
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
        for (var typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                String className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
                Set<String> injectedServices = collectInjectedServices(clazz);
                for (String service : injectedServices) {
                    relations.add(new CallRelation(className, null, service));
                }
            }
        }

        // 遍历所有方法，抽取方法调用
        for (var typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                String callerClass = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
                for (MethodDeclaration method : clazz.getMethods()) {
                    method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        String calledMethod = methodCall.getNameAsString();
                        String callerMethod = method.getNameAsString();
                        relations.add(new CallRelation(callerClass, callerMethod, calledMethod));
                    });
                }
            }
        }

        return relations;
    }

    /**
     * 收集所有注入的服务依赖
     * 支持：字段注入、构造器注入、Lombok注入
     */
    private Set<String> collectInjectedServices(ClassOrInterfaceDeclaration clazz) {
        Set<String> services = new HashSet<>();

        // 处理构造器注入 — 通过解析构造参数中的类型名
        clazz.getConstructors().forEach(constructor -> {
            for (var param : constructor.getParameters()) {
                String typeName = param.getType().asString();
                if (typeName != null && !typeName.isEmpty()) {
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    services.add(typeName);
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
                    String typeName = variable.getType().asString();
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    services.add(typeName);
                }
            }
        }
        return services;
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
