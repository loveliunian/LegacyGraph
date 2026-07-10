package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 测试用例抽取器 — 解析 @Test、@ParameterizedTest 等注解。
 * 抽取结果用于构建 TestCase 和 Assertion 节点。
 */
@Slf4j
@Component
public class TestCaseExtractor {

    private final JavaParser javaParser;
    private static final Set<String> ASSERT_METHODS = Set.of(
        "assertEquals", "assertTrue", "assertFalse", "assertNotNull", "assertNull",
        "assertThrows", "assertThat", "verify"
    );

    public TestCaseExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从测试文件抽取测试用例信息。
     */
    public List<TestCaseFact> extractFromFile(Path javaFile) throws IOException {
        List<TestCaseFact> result = new ArrayList<>();
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
            typeDecl.getMethods().forEach(method -> {
                method.getAnnotations().forEach(annotation -> {
                    String annotationName = annotation.getNameAsString();
                    if (isTestAnnotation(annotationName)) {
                        TestCaseFact fact = new TestCaseFact();
                        fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
                        fact.setMethodName(method.getNameAsString());
                        fact.setMethodSignature(MethodSignatureSupport.build(method));
                        fact.setAnnotationType(annotationName);
                        fact.setSourcePath(javaFile.toString());
                        fact.setStartLine(method.getBegin().map(p -> p.line).orElse(null));
                        fact.setEndLine(method.getEnd().map(p -> p.line).orElse(null));

                        // 提取断言调用
                        List<AssertionFact> assertions = extractAssertions(method);
                        fact.setAssertions(assertions);
                        result.add(fact);
                    }
                });
            });
        }

        return result;
    }

    private boolean isTestAnnotation(String name) {
        return "Test".equals(name) 
            || "ParameterizedTest".equals(name) 
            || "RepeatedTest".equals(name)
            || "TestTemplate".equals(name);
    }

    private List<AssertionFact> extractAssertions(MethodDeclaration method) {
        List<AssertionFact> assertions = new ArrayList<>();
        method.findAll(MethodCallExpr.class).forEach(call -> {
            String methodName = call.getNameAsString();
            if (ASSERT_METHODS.contains(methodName)) {
                AssertionFact assertion = new AssertionFact();
                assertion.setAssertionType(methodName);
                assertion.setStartLine(call.getBegin().map(p -> p.line).orElse(null));
                assertion.setEndLine(call.getEnd().map(p -> p.line).orElse(null));

                // 提取断言参数（简化：取第一个参数作为描述）
                if (!call.getArguments().isEmpty()) {
                    assertion.setExpectedValue(call.getArgument(0).toString());
                }
                assertions.add(assertion);
            }
        });
        return assertions;
    }

    /**
     * 测试用例事实。
     */
    @Data
    public static class TestCaseFact {
        private String className;
        private String methodName;
        private String methodSignature;
        private String annotationType;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
        private List<AssertionFact> assertions;
        /** 测试内调用的被测方法列表（用于 VERIFIED_BY 边接线） */
        private List<InvokedMethodCall> invokedMethodCalls = new ArrayList<>();
    }

    /**
     * 测试内调用的被测方法事实。
     */
    @Data
    public static class InvokedMethodCall {
        private String methodName;
    }

    /**
     * 断言事实。
     */
    @Data
    public static class AssertionFact {
        private String assertionType;
        private String expectedValue;
        private Integer startLine;
        private Integer endLine;
    }
}
