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
 * 业务规则扫描器 - 从校验注解和规则方法提取 BusinessRule 节点
 * 
 * 扫描目标：
 * - JSR-303 校验注解: @NotNull, @NotBlank, @Size, @Min, @Max, @Pattern, @Email
 * - 自定义校验注解: @Valid*, @Check*
 * - 方法名包含 validate/check/verify/assert 的方法
 */
@Slf4j
@Component
public class BusinessRuleExtractor {

    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max",
            "Pattern", "Email", "Positive", "Negative", "DecimalMin", "DecimalMax",
            "Digits", "Past", "Future", "AssertTrue", "AssertFalse",
            "Valid", "Validated"
    );

    private static final Set<String> RULE_METHOD_PREFIXES = Set.of(
            "validate", "check", "verify", "assert", "ensure"
    );

    private final JavaParser javaParser;

    public BusinessRuleExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    public List<NodeExtractionResult> extract(File file) {
        List<NodeExtractionResult> results = new ArrayList<>();
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return results;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = file.getAbsolutePath();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();

                // 字段校验注解
                clazz.getFields().forEach(field -> {
                    field.getAnnotations().forEach(annotation -> {
                        String annotName = annotation.getNameAsString();
                        if (VALIDATION_ANNOTATIONS.contains(annotName)) {
                            String fieldName = field.getVariables().get(0).getNameAsString();
                            String ruleName = className + "." + fieldName + "@" + annotName;
                            String nodeKey = "rule:" + ruleName.toLowerCase();

                            NodeExtractionResult result = NodeExtractionResult.builder()
                                    .nodeType("BusinessRule")
                                    .nodeKey(nodeKey)
                                    .displayName(ruleName)
                                    .description("字段校验规则: " + fieldName + " " + annotName)
                                    .sourcePath(filePath)
                                    .sourceType("CODE_AST")
                                    .confidence(0.85)
                                    .properties(Map.of(
                                            "className", className,
                                            "fieldName", fieldName,
                                            "annotation", annotName,
                                            "ruleType", "FIELD_VALIDATION"
                                    ))
                                    .build();
                            results.add(result);
                        }
                    });
                });

                // 方法级校验
                clazz.getMethods().forEach(method -> {
                    String methodName = method.getNameAsString();
                    if (isRuleMethod(methodName)) {
                        String ruleName = className + "." + methodName;
                        String nodeKey = "rule:" + ruleName.toLowerCase();

                        NodeExtractionResult result = NodeExtractionResult.builder()
                                .nodeType("BusinessRule")
                                .nodeKey(nodeKey)
                                .displayName(ruleName)
                                .description("业务校验方法: " + ruleName)
                                .sourcePath(filePath)
                                .sourceType("CODE_AST")
                                .confidence(0.75)
                                .properties(Map.of(
                                        "className", className,
                                        "methodName", methodName,
                                        "ruleType", "METHOD_VALIDATION"
                                ))
                                .build();
                        results.add(result);
                    }
                });
            });
        } catch (Exception e) {
            log.warn("Failed to parse business rules from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    private boolean isRuleMethod(String methodName) {
        String lower = methodName.toLowerCase();
        return RULE_METHOD_PREFIXES.stream().anyMatch(lower::startsWith);
    }
}
