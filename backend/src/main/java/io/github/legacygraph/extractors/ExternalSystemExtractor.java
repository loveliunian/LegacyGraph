package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部系统调用抽取器 — 解析 RestTemplate、FeignClient、WebClient 等 HTTP 客户端调用。
 * 抽取结果用于构建 ExternalSystem 节点。
 */
@Slf4j
@Component
public class ExternalSystemExtractor {

    private final JavaParser javaParser;
    private static final Set<String> HTTP_METHODS = Set.of(
        "getForObject", "getForEntity", "postForObject", "postForEntity",
        "put", "delete", "exchange", "execute"
    );

    public ExternalSystemExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取外部系统调用信息。
     */
    public List<ExternalCallFact> extractFromFile(Path javaFile) throws IOException {
        List<ExternalCallFact> result = new ArrayList<>();
        if (!Files.exists(javaFile) || !Files.isReadable(javaFile)) {
            return result;
        }

        String content = Files.readString(javaFile);
        ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(content);
        } catch (RuntimeException e) {
            log.warn("JavaParser crashed on {}: {}", javaFile, e.getMessage());
            return result;
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return result;
        }

        CompilationUnit cu = parseResult.getResult().get();
        String className = cu.getPrimaryTypeName().orElse("Unknown");
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        for (var typeDecl : cu.getTypes()) {
            // 检测 @FeignClient 注解
            typeDecl.getAnnotations().forEach(annotation -> {
                if ("FeignClient".equals(annotation.getNameAsString())) {
                    ExternalCallFact fact = new ExternalCallFact();
                    fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
                    fact.setClientType("FeignClient");
                    fact.setSourcePath(javaFile.toString());
                    fact.setStartLine(typeDecl.getBegin().map(p -> p.line).orElse(null));
                    fact.setEndLine(typeDecl.getEnd().map(p -> p.line).orElse(null));

                    // 提取 URL/name 参数
                    if (annotation.isSingleMemberAnnotationExpr()) {
                        fact.setServiceName(cleanValue(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString()));
                    } else if (annotation.isNormalAnnotationExpr()) {
                        for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                            String name = pair.getNameAsString();
                            String value = pair.getValue().toString();
                            switch (name) {
                                case "name", "value" -> fact.setServiceName(cleanValue(value));
                                case "url" -> fact.setBaseUrl(cleanValue(value));
                            }
                        }
                    }
                    result.add(fact);
                }
            });

            // 检测 RestTemplate/WebClient 方法调用
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                clazz.getMethods().forEach(method -> {
                    method.findAll(MethodCallExpr.class).forEach(call -> {
                        String methodName = call.getNameAsString();
                        if (HTTP_METHODS.contains(methodName)) {
                            call.getScope().ifPresent(scope -> {
                                String scopeStr = scope.toString();
                                if (scopeStr.contains("restTemplate") || scopeStr.contains("webClient") 
                                    || scopeStr.contains("httpClient")) {
                                    ExternalCallFact fact = new ExternalCallFact();
                                    fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
                                    fact.setMethodName(method.getNameAsString());
                                    fact.setMethodSignature(MethodSignatureSupport.build(method));
                                    fact.setClientType(scopeStr.contains("restTemplate") ? "RestTemplate" : 
                                        (scopeStr.contains("webClient") ? "WebClient" : "HttpClient"));
                                    fact.setSourcePath(javaFile.toString());
                                    fact.setStartLine(call.getBegin().map(p -> p.line).orElse(null));
                                    fact.setEndLine(call.getEnd().map(p -> p.line).orElse(null));

                                    // 尝试提取 URL 参数（第一个参数通常是 URL）
                                    if (!call.getArguments().isEmpty()) {
                                        String firstArg = call.getArgument(0).toString();
                                        if (firstArg.startsWith("\"") && firstArg.endsWith("\"")) {
                                            fact.setBaseUrl(cleanValue(firstArg));
                                        }
                                    }
                                    result.add(fact);
                                }
                            });
                        }
                    });
                });
            }
        }

        return result;
    }

    private String cleanValue(String value) {
        if (value == null) return null;
        return value.replace("\"", "").replace("'", "").trim();
    }

    /**
     * 外部系统调用事实。
     */
    @Data
    public static class ExternalCallFact {
        private String className;
        private String methodName;
        private String methodSignature;
        private String clientType; // FeignClient, RestTemplate, WebClient, HttpClient
        private String serviceName;
        private String baseUrl;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }
}
