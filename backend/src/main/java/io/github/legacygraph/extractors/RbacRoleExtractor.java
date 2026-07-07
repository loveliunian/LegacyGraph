package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * RBAC 角色扫描器 - 从注解中提取 Role 节点
 * 
 * 扫描目标：
 * - @PreAuthorize("hasRole('ADMIN')") / @PreAuthorize("hasAuthority('user:read')")
 * - @RequiresRoles("admin") / @RequiresRoles({"admin", "user"})
 * - @Secured("ROLE_ADMIN")
 * - Spring Security @RolesAllowed
 */
@Slf4j
@Component
public class RbacRoleExtractor {

    private static final Set<String> ROLE_ANNOTATIONS = Set.of(
            "PreAuthorize", "RequiresRoles", "Secured", "RolesAllowed"
    );

    private final JavaParser javaParser;

    public RbacRoleExtractor() {
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

                // 类级注解
                extractRolesFromAnnotations(clazz.getAnnotations(), filePath, className, null, results);

                // 方法级注解
                clazz.getMethods().forEach(method -> {
                    extractRolesFromAnnotations(method.getAnnotations(), filePath, className, method.getNameAsString(), results);
                });
            });
        } catch (Exception e) {
            log.warn("Failed to parse RBAC roles from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    private void extractRolesFromAnnotations(List<AnnotationExpr> annotations, String filePath,
                                              String className, String methodName,
                                              List<NodeExtractionResult> results) {
        for (AnnotationExpr annotation : annotations) {
            String annotName = annotation.getNameAsString();
            if (!ROLE_ANNOTATIONS.contains(annotName)) continue;

            Set<String> roles = parseRoleValues(annotation);
            for (String role : roles) {
                String nodeKey = "role:" + role.toLowerCase();
                String displayName = role;
                String context = methodName != null
                        ? className + "." + methodName
                        : className;

                NodeExtractionResult result = NodeExtractionResult.builder()
                        .nodeType("Role")
                        .nodeKey(nodeKey)
                        .displayName(displayName)
                        .description("RBAC 角色: " + role + " (@" + annotName + " on " + context + ")")
                        .sourcePath(filePath)
                        .sourceType("CODE_AST")
                        .confidence(0.95)
                        .properties(Map.of(
                                "role", role,
                                "annotation", annotName,
                                "context", context
                        ))
                        .build();
                results.add(result);
            }
        }
    }

    private Set<String> parseRoleValues(AnnotationExpr annotation) {
        Set<String> roles = new LinkedHashSet<>();
        String annotName = annotation.getNameAsString();

        if (annotation.isSingleMemberAnnotationExpr()) {
            String value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
            parseRoleString(value, annotName, roles);
        } else if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "roles".equals(pair.getNameAsString())) {
                    parseRoleString(pair.getValue().toString(), annotName, roles);
                }
            }
        }
        return roles;
    }

    private void parseRoleString(String value, String annotName, Set<String> roles) {
        // 去除引号
        value = value.replace("\"", "").trim();

        // @PreAuthorize("hasRole('ADMIN')") → ADMIN
        if ("PreAuthorize".equals(annotName)) {
            if (value.contains("hasRole(")) {
                String extracted = value.replaceAll(".*hasRole\\(['\"]?([^'\"]+)['\"]?\\).*", "$1");
                if (!extracted.isEmpty() && !extracted.equals(value)) {
                    roles.add(extracted);
                }
            }
            if (value.contains("hasAuthority(")) {
                String extracted = value.replaceAll(".*hasAuthority\\(['\"]?([^'\"]+)['\"]?\\).*", "$1");
                if (!extracted.isEmpty() && !extracted.equals(value)) {
                    roles.add(extracted);
                }
            }
        } else {
            // @RequiresRoles, @Secured, @RolesAllowed — 直接值或数组
            // 处理数组: { "admin", "user" }
            value = value.replace("{", "").replace("}", "");
            for (String part : value.split(",")) {
                String role = part.trim().replace("\"", "").replace("'", "");
                if (!role.isEmpty()) {
                    // 去除 ROLE_ 前缀
                    if (role.startsWith("ROLE_")) {
                        role = role.substring(5);
                    }
                    roles.add(role);
                }
            }
        }
    }
}
