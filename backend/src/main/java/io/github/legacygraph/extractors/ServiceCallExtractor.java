package io.github.legacygraph.extractors;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import com.github.javaparser.JavaParser;

/**
 * Service调用关系抽取器
 * 抽取Controller -> Service, Service -> Service, Service -> Mapper的调用关系
 */
@Slf4j
public class ServiceCallExtractor {

    private final JavaParser javaParser;

    public ServiceCallExtractor() {
        this.javaParser = new JavaParser();
    }

    /**
     * 抽取调用关系结果
     */
    @Data
    public static class CallRelation {
        private String callerClass;
        private String callerMethod;
        private String targetClass;
        private String targetMethod;
        private String sourcePath;
        private Integer lineNumber;
        private CallType callType;

        public enum CallType {
            CONTROLLER_TO_SERVICE,
            SERVICE_TO_SERVICE,
            SERVICE_TO_MAPPER,
            METHOD_TO_METHOD
        }
    }

    /**
     * 从Java文件抽取调用关系
     */
    public List<CallRelation> extractFromFile(Path javaFile) throws IOException {
        List<CallRelation> relations = new ArrayList<>();

        ParseResult<CompilationUnit> result = javaParser.parse(javaFile);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java file for call extraction: {}", javaFile);
            return relations;
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = packageName.isEmpty() ?
                    clazz.getNameAsString() :
                    packageName + "." + clazz.getNameAsString();

            // 收集注入的字段 (Autowired, 构造器注入等)
            Set<String> injectedServices = collectInjectedServices(clazz);

            // 遍历每个方法，查找方法调用
            for (MethodDeclaration method : clazz.getMethods()) {
                String methodName = method.getNameAsString();
                int line = method.getBegin().map(p -> p.line).orElse(0);

                // 查找所有方法调用表达式
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    // 尝试解析被调用的方法所属类
                    Expression scope = call.getScope().orElse(null);
                    if (scope != null) {
                        String scopeType = scope.calculateResolvedType().toString();
                        String calledMethodName = call.getNameAsString();

                        // 判断调用类型
                        CallRelation.CallType callType = inferCallType(className, scopeType);

                        CallRelation relation = new CallRelation();
                        relation.setCallerClass(className);
                        relation.setCallerMethod(methodName);
                        relation.setTargetClass(scopeType);
                        relation.setTargetMethod(calledMethodName);
                        relation.setSourcePath(javaFile.toString());
                        relation.setLineNumber(line);
                        relation.setCallType(callType);

                        relations.add(relation);
                    }
                }
            }
        }

        return relations;
    }

    /**
     * 收集注入的服务依赖
     */
    private Set<String> collectInjectedServices(ClassOrInterfaceDeclaration clazz) {
        Set<String> services = new HashSet<>();

        // 字段注入 @Autowired
        for (FieldDeclaration field : clazz.getFields()) {
            boolean hasAutowired = field.getAnnotations().stream()
                    .anyMatch(a -> "Autowired".equals(a.getNameAsString()) || "Inject".equals(a.getNameAsString()));
            if (hasAutowired) {
                for (VariableDeclarator variable : field.getVariables())
                    variable.getType().ifClassOrInterfaceType(type -> {
                        String typeName = type.getNameAsString();
                        services.add(typeName);
                    });
                }
            }


        // TODO: 构造器注入和Lombok注入处理
        return services;
    }

    /**
     * 根据类名推断调用类型
     */
    private CallRelation.CallType inferCallType(String callerClass, String targetClass) {
        if (callerClass.contains("Controller") && (targetClass.contains("Service") || targetClass.contains("service"))) {
            return CallRelation.CallType.CONTROLLER_TO_SERVICE;
        }
        if ((callerClass.contains("Service") || callerClass.contains("service")) &&
            (targetClass.contains("Mapper") || targetClass.contains("mapper") || targetClass.contains("Dao"))) {
            return CallRelation.CallType.SERVICE_TO_MAPPER;
        }
        if ((callerClass.contains("Service") || callerClass.contains("service")) &&
            (targetClass.contains("Service") || targetClass.contains("service"))) {
            return CallRelation.CallType.SERVICE_TO_SERVICE;
        }
        return CallRelation.CallType.METHOD_TO_METHOD;
    }
}
