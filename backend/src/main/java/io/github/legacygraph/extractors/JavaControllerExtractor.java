package io.github.legacygraph.extractors;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import io.github.legacygraph.model.ApiFact;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java Controller 抽取器
 * 从Spring Boot Controller中抽取API接口信息
 */
@Slf4j
public class JavaControllerExtractor {

    private final com.github.javaparser.JavaParser javaParser;

    public JavaControllerExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        // 使用当前javaparser支持的最高语言级别（3.28.2 → JAVA_26），兼容 Java 8~26 所有语法
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new com.github.javaparser.JavaParser(config);
    }

    /**
     * 从Java文件中抽取Controller的API信息
     */
    public List<ApiFact> extractFromFile(Path javaFile) throws IOException {
        List<ApiFact> result = new ArrayList<>();

        // 先读入内存再解析，避免 I/O 竞争读到半截文件
        ParseResult<CompilationUnit> resultCU;
        try {
            resultCU = javaParser.parse(Files.readString(javaFile));
        } catch (RuntimeException e) {
            // JavaParser 词法分析器偶发内部崩溃（如 IndexOutOfBounds），重试一次
            log.warn("JavaParser crashed on first parse attempt (will retry): {} — {}", javaFile, e.getMessage());
            try {
                resultCU = javaParser.parse(Files.readString(javaFile));
            } catch (RuntimeException e2) {
                log.warn("Failed to parse Java file (JavaParser crash after retry): {}", javaFile);
                log.warn("Parse error: {}", e2.getMessage());
                return result;
            }
        }
        if (!resultCU.isSuccessful() || resultCU.getResult().isEmpty()) {
            // 偶发 I/O 竞争导致读入不完整 → 重读源文件并重试一次
            try {
                resultCU = javaParser.parse(Files.readString(javaFile));
            } catch (RuntimeException e) {
                log.warn("Failed to parse Java file (JavaParser crash on retry): {}", javaFile);
                log.warn("Parse error: {}", e.getMessage());
                return result;
            }
        }
        if (!resultCU.isSuccessful() || resultCU.getResult().isEmpty()) {
            log.warn("Failed to parse Java file (after retry): {}", javaFile);
            logParseProblems(resultCU);
            return result;
        }

        CompilationUnit cu = resultCU.getResult().get();

        // 遍历所有类
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!hasControllerAnnotation(clazz)) {
                continue;
            }

            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
            String className = clazz.getNameAsString();
            String basePath = resolveClassRequestMapping(clazz);

            // 遍历类中所有方法
            for (MethodDeclaration method : clazz.getMethods()) {
                Optional<MappingInfo> mappingOpt = resolveMethodMapping(method);
                if (mappingOpt.isEmpty()) {
                    continue;
                }

                MappingInfo mapping = mappingOpt.get();
                ApiFact fact = new ApiFact();
                fact.setHttpMethod(mapping.getHttpMethod());
                fact.setPath(mapping.getPath());
                fact.setFullPath(joinPath(basePath, mapping.getPath()));
                fact.setControllerClass(className);
                fact.setControllerPackage(packageName);
                fact.setMethodName(method.getNameAsString());
                fact.setRequestParams(resolveParameters(method));
                fact.setRequestBody(resolveRequestBody(method));
                fact.setResponseType(method.getTypeAsString());
                fact.setAnnotations(collectAnnotations(clazz, method));
                fact.setPermissions(resolvePermissions(method));
                fact.setSourcePath(javaFile.toString());
                fact.setStartLine(method.getBegin().map(p -> p.line).orElse(null));
                fact.setEndLine(method.getEnd().map(p -> p.line).orElse(null));
                fact.setMethodSignature(buildMethodSignature(method));

                result.add(fact);
            }
        }

        return result;
    }

    /**
     * 检查是否有Controller注解
     */
    private boolean hasControllerAnnotation(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return "RestController".equals(name) || "Controller".equals(name);
                });
    }

    /**
     * 解析类级别的@RequestMapping
     */
    private String resolveClassRequestMapping(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr annotation : clazz.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("RequestMapping".equals(name)) {
                return extractPath(annotation);
            }
        }
        return "";
    }

    /**
     * 解析方法级别的映射
     */
    private Optional<MappingInfo> resolveMethodMapping(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            String httpMethod = null;

            switch (name) {
                case "GetMapping":
                    httpMethod = "GET";
                    break;
                case "PostMapping":
                    httpMethod = "POST";
                    break;
                case "PutMapping":
                    httpMethod = "PUT";
                    break;
                case "DeleteMapping":
                    httpMethod = "DELETE";
                    break;
                case "PatchMapping":
                    httpMethod = "PATCH";
                    break;
                case "RequestMapping":
                    // 检查method属性
                    httpMethod = extractHttpMethod(annotation);
                    break;
                default:
                    continue;
            }

            String path = extractPath(annotation);
            return Optional.of(new MappingInfo(httpMethod, path));
        }
        return Optional.empty();
    }

    /**
     * 从注解中提取路径
     */
    private String extractPath(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString()
                    .replace("\"", "").trim();
        } else if (annotation instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "").trim();
                }
            }
        }
        return "";
    }

    /**
     * 从@RequestMapping提取HTTP方法
     */
    private String extractHttpMethod(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                if ("method".equals(pair.getNameAsString())) {
                    String value = pair.getValue().toString();
                    if (value.contains("GET")) return "GET";
                    if (value.contains("POST")) return "POST";
                    if (value.contains("PUT")) return "PUT";
                    if (value.contains("DELETE")) return "DELETE";
                    if (value.contains("PATCH")) return "PATCH";
                }
            }
        }
        return "GET"; // 默认GET
    }

    /**
     * 拼接路径
     */
    private String joinPath(String basePath, String methodPath) {
        if (basePath.isEmpty()) return methodPath;
        if (methodPath.isEmpty()) return basePath;
        if (!basePath.endsWith("/") && !methodPath.startsWith("/")) {
            return basePath + "/" + methodPath;
        }
        if (basePath.endsWith("/") && methodPath.startsWith("/")) {
            return basePath + methodPath.substring(1);
        }
        return basePath + methodPath;
    }

    /**
     * 解析方法参数
     */
    private List<ApiFact.ApiParameter> resolveParameters(MethodDeclaration method) {
        List<ApiFact.ApiParameter> params = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            ApiFact.ApiParameter p = new ApiFact.ApiParameter();
            p.setName(param.getNameAsString());
            p.setType(param.getTypeAsString());
            // In newer JavaParser, check if the type is an Optional-like nullable type
            boolean isNullable = param.getType().toString().contains("Optional")
                    || param.getType().toString().endsWith("?");
            p.setRequired(!isNullable);
            params.add(p);
        }
        return params;
    }

    /**
     * 构建方法签名: methodName(paramType1, paramType2)
     * 与 JavaStructureExtractor 生成的 Method key 格式对齐
     */
    private String buildMethodSignature(MethodDeclaration method) {
        String methodName = method.getNameAsString();
        String params = method.getParameters().stream()
                .map(p -> {
                    String type = p.getTypeAsString();
                    // 提取简单类型名（去掉包名）
                    if (type.contains(".")) {
                        type = type.substring(type.lastIndexOf('.') + 1);
                    }
                    return type;
                })
                .collect(java.util.stream.Collectors.joining(", "));
        return methodName + "(" + params + ")";
    }

    /**
     * 解析请求体
     */
    private ApiFact.ApiParameter resolveRequestBody(MethodDeclaration method) {
        for (Parameter param : method.getParameters()) {
            boolean hasRequestBody = param.getAnnotations().stream()
                    .anyMatch(a -> "RequestBody".equals(a.getNameAsString()));
            if (hasRequestBody) {
                ApiFact.ApiParameter p = new ApiFact.ApiParameter();
                p.setName(param.getNameAsString());
                p.setType(param.getTypeAsString());
                p.setRequired(true);
                return p;
            }
        }
        return null;
    }

    /**
     * 收集所有注解
     */
    private List<String> collectAnnotations(ClassOrInterfaceDeclaration clazz, MethodDeclaration method) {
        List<String> annotations = new ArrayList<>();
        clazz.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));
        method.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));
        return annotations;
    }

    /**
     * 解析权限注解
     */
    private List<String> resolvePermissions(MethodDeclaration method) {
        List<String> permissions = new ArrayList<>();

        // 处理常见权限注解
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            switch (name) {
                case "RequiresPermissions":
                case "PreAuthorize":
                    String permission = extractPermissionValue(annotation);
                    if (permission != null) {
                        permissions.add(permission);
                    }
                    break;
                case "RequiresRoles":
                    String role = extractPermissionValue(annotation);
                    if (role != null) {
                        permissions.add("role:" + role);
                    }
                    break;
                default:
                    // 自定义权限注解
                    if (name.contains("Permission") || name.contains("Auth")) {
                        String perm = extractPermissionValue(annotation);
                        if (perm != null) {
                            permissions.add(perm);
                        }
                    }
            }
        }

        return permissions;
    }

    /**
     * 提取权限值
     */
    private String extractPermissionValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString()
                    .replace("\"", "").replace("hasAuthority('", "").replace("')", "").trim();
        } else if (annotation instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "").trim();
                }
            }
        }
        return null;
    }

    /**
     * 安全输出解析问题概要。使用 getMessage() 仅输出一行描述，
     * 避免 getVerboseMessage() 输出超长源码上下文或 toString() 打印 stack trace。
     */
    static void logParseProblems(ParseResult<?> result) {
        var problems = result.getProblems();
        if (problems == null || problems.isEmpty()) return;
        for (int i = 0; i < problems.size(); i++) {
            try {
                log.warn("Parse problem [{}]: {}", i, problems.get(i).getMessage());
            } catch (Exception ex) {
                log.warn("Parse problem [{}]: <FAILED getMessage(): {}>", i, ex.getMessage());
            }
        }
    }

    /**
     * 映射信息内部类
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class MappingInfo {
        private String httpMethod;
        private String path;
    }
}
