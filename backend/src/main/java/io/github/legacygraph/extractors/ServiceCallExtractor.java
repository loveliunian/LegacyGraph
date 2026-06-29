package io.github.legacygraph.extractors;

import com.github.javaparser.ParseResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        for (Type type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterface) type;
                String className = clazz.getFullyQualifiedName().toString();
                Set<String> injectedServices = collectInjectedServices(clazz);
                for (String service : injectedServices) {
                    // 这里收集到所有注入的依赖，后续在调用关系抽取中使用
                    relations.add(new CallRelation(className, null, service));
                }
            }
        }

        // 遍历所有方法，抽取方法调用
        for (Type type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterface) type;
                String callerClass = clazz.getFullyQualifiedName().toString();
                for (MethodDeclaration method : clazz.getMethods()) {
                    method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        String calledMethod = methodCall.getNameAsString();
                        String callerMethod = method.getNameAsString();
                        // 完整方法名应该是className.methodName
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

        // 处理构造器注入
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                if (parameter.getType() instanceof Class<?>) {
                    Class<?> paramClass = (Class<?>) parameter.getType();
                    services.add(classToTypeName(paramClass));
                }
            }
        }

        // Lombok 默认会生成一个包含所有字段的构造器，如果已经有一个无参数构造器说明已经处理了
        boolean hasNoArgsConstructor = false;
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0) {
                hasNoArgsConstructor = true;
                break;
            }
        }
        if (!hasNoArgsConstructor) {
            // 父类处理
            Class<?> superClazz = clazz.getSuperclass();
            if (superClazz != null && !Object.class.equals(superClazz)) {
                services.addAll(collectInjectedServices(superClazz));
            }
        }

        // 处理字段注入
        for (FieldDeclaration field : clazz.getDeclaredFields()) {
            if (field.getAnnotations().stream().anyMatch(a ->
                a.getAnnotations().stream().anyMatch(ann ->
                    ann.getNameAsString().equalsIgnoreCase("Autowired") ||
                    ann.getNameAsString().equalsIgnoreCase("Inject")))) {
                for (var variable : field.getVariables()) {
                    Type fieldType = variable.getType();
                    if (fieldType instanceof Class<?>) {
                        Class<?> fieldClass = (Class<?>) fieldType;
                        services.add(classToTypeName(fieldClass));
                    }
                }
            }
        }
        return services;
    }

    /**
     * 类型转名称，处理泛型
     */
    private String classToTypeName(Class<?> clazz) {
        return clazz.getName();
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
        private String targetClass;      // 被调用方所属类名（从注入依赖或类型推导）
        private String targetMethod;     // 被调用方法名（同 calledMethod，更语义化命名）
        private String sourcePath;       // 源文件路径
        private Integer lineNumber;      // 行号

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
