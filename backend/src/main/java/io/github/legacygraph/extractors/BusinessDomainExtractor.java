package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 业务域扫描器 - 从 Controller @RequestMapping 路径和 @Service 注解值推断 BusinessDomain 节点
 *
 * 扫描策略（纯 AST，不依赖文件/目录命名）：
 * 1. Controller 类上的 @RequestMapping 路径前缀（如 /api/user → 用户域）
 * 2. @Service("domainName") 注解显式指定的值
 * 3. @Tag(name="用户管理") 等 Swagger/OpenAPI 分组注解
 * 4. 收集所有来源后去重归并
 */
@Slf4j
@Component
public class BusinessDomainExtractor {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );

    private static final Set<String> DOMAIN_SOURCE_ANNOTATIONS = Set.of(
            "RequestMapping", "Tag", "Api"
    );

    private final JavaParser javaParser;

    public BusinessDomainExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    public List<NodeExtractionResult> extract(File file) {
        List<NodeExtractionResult> results = new ArrayList<>();
        if (!file.getName().endsWith(".java")) return results;

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return results;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = file.getAbsolutePath();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                // 策略1: Controller 的 @RequestMapping 路径前缀
                extractDomainFromController(clazz, filePath, results);

                // 策略2: @Service("xxx") 显式命名
                extractDomainFromServiceAnnotation(clazz, filePath, results);

                // 策略3: Swagger @Tag(name="xxx") 分组
                extractDomainFromSwaggerTag(clazz, filePath, results);
            });
        } catch (Exception e) {
            log.warn("Failed to parse business domain from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    private void extractDomainFromController(ClassOrInterfaceDeclaration clazz, String filePath,
                                              List<NodeExtractionResult> results) {
        boolean isController = clazz.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .anyMatch(CONTROLLER_ANNOTATIONS::contains);
        if (!isController) return;

        for (AnnotationExpr ann : clazz.getAnnotations()) {
            if (!"RequestMapping".equals(ann.getNameAsString())) continue;

            String path = extractStringAttribute(ann, "value");
            if (path == null) path = extractStringAttribute(ann, "path");
            if (path == null || path.isEmpty() || "/".equals(path)) continue;

            // /api/user/list → user, /system/role → role
            String[] segments = path.split("/");
            for (String seg : segments) {
                if (seg.isEmpty() || "api".equals(seg) || "v1".equals(seg) || "v2".equals(seg)) continue;

                String domainName = seg;
                String nodeKey = "domain:" + domainName.toLowerCase();

                NodeExtractionResult result = NodeExtractionResult.builder()
                        .nodeType("BusinessDomain")
                        .nodeKey(nodeKey)
                        .displayName(domainName)
                        .description("业务域: " + domainName + " (Controller 路径推断)")
                        .sourcePath(filePath)
                        .sourceType("CODE_AST")
                        .confidence(0.7)
                        .properties(Map.of(
                                "source", "CONTROLLER_PATH",
                                "pathPrefix", path,
                                "domainName", domainName
                        ))
                        .build();
                results.add(result);
                break; // 只取第一个有意义的 segment
            }
        }
    }

    private void extractDomainFromServiceAnnotation(ClassOrInterfaceDeclaration clazz, String filePath,
                                                      List<NodeExtractionResult> results) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getNameAsString();
            if (!"Service".equals(name) && !"Component".equals(name)) continue;

            String value = extractStringAttribute(ann, "value");
            if (value == null || value.isEmpty()) continue;

            // @Service("userDomain") → 用户域
            String nodeKey = "domain:" + value.toLowerCase();
            NodeExtractionResult result = NodeExtractionResult.builder()
                    .nodeType("BusinessDomain")
                    .nodeKey(nodeKey)
                    .displayName(value)
                    .description("业务域: " + value + " (显式注解命名)")
                    .sourcePath(filePath)
                    .sourceType("CODE_AST")
                    .confidence(0.85)
                    .properties(Map.of(
                            "source", "SERVICE_ANNOTATION",
                            "annotationValue", value,
                            "domainName", value
                    ))
                    .build();
            results.add(result);
        }
    }

    private void extractDomainFromSwaggerTag(ClassOrInterfaceDeclaration clazz, String filePath,
                                               List<NodeExtractionResult> results) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getNameAsString();
            if (!"Tag".equals(name) && !"Api".equals(name)) continue;

            String tagValue = extractStringAttribute(ann, "name");
            if (tagValue == null) tagValue = extractStringAttribute(ann, "tags");
            if (tagValue == null || tagValue.isEmpty()) continue;

            String nodeKey = "domain:" + tagValue.toLowerCase().replace(" ", "_");
            NodeExtractionResult result = NodeExtractionResult.builder()
                    .nodeType("BusinessDomain")
                    .nodeKey(nodeKey)
                    .displayName(tagValue)
                    .description("业务域: " + tagValue + " (Swagger 分组)")
                    .sourcePath(filePath)
                    .sourceType("CODE_AST")
                    .confidence(0.8)
                    .properties(Map.of(
                            "source", "SWAGGER_TAG",
                            "tagValue", tagValue,
                            "domainName", tagValue
                    ))
                    .build();
            results.add(result);
        }
    }

    private String extractStringAttribute(AnnotationExpr ann, String attrName) {
        if (ann.isSingleMemberAnnotationExpr()) {
            if ("value".equals(attrName)) {
                return ann.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", "");
            }
            return null;
        }
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (attrName.equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        // @RequestMapping("/api/user") — marker with string literal
        if ("value".equals(attrName) && ann.isMarkerAnnotationExpr()) {
            return null;
        }
        return null;
    }
}
