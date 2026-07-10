package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RBAC 角色扫描器 - 从注解中提取 Role / Permission 节点
 *
 * 扫描目标：
 * - @PreAuthorize("hasRole('ADMIN')") / @PreAuthorize("hasAuthority('user:read')") / @PreAuthorize("hasPermission('x')")
 * - @RequiresRoles("admin") / @RequiresRoles({"admin", "user"})
 * - @Secured("ROLE_ADMIN")
 * - Spring Security @RolesAllowed
 *
 * <p>统一由本类处理 @PreAuthorize SpEL，同时抽取 Role（hasRole/hasAnyRole）
 * 与 Permission（hasAuthority/hasAnyAuthority/hasPermission），并通过 Role 节点 properties.permissions
 * 记录二者关联，供 GraphBuilder.buildRbacRoleGraph 建 GRANTS 边。</p>
 */
@Slf4j
@Component
public class RbacRoleExtractor {

    private static final Set<String> ROLE_ANNOTATIONS = Set.of(
            "PreAuthorize", "RequiresRoles", "Secured", "RolesAllowed"
    );

    /** @PreAuthorize SpEL：hasRole('X') / hasAnyRole('X','Y') → Role */
    private static final Pattern HAS_ROLE_PATTERN = Pattern.compile(
            "has(?:Any)?Role\\(\\s*['\"]?([^'\"\\)]+)['\"]?\\s*\\)");

    /** @PreAuthorize SpEL：hasAuthority('X') / hasAnyAuthority('X','Y') / hasPermission('X') → Permission */
    private static final Pattern HAS_AUTHORITY_PATTERN = Pattern.compile(
            "has(?:Any)?(?:Authority|Permission)\\(\\s*['\"]?([^'\"\\)]+)['\"]?\\s*\\)");

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

            Set<String> roles = new LinkedHashSet<>();
            Set<String> permissions = new LinkedHashSet<>();
            Map<String, Object> spelProps = new HashMap<>();
            parseRbacValues(annotation, annotName, roles, permissions, spelProps);

            String context = methodName != null
                    ? className + "." + methodName
                    : className;

            // 创建 Role 节点；同时把该注解内关联的 permissions 记录到 properties，供 GraphBuilder 建 GRANTS 边
            for (String role : roles) {
                String nodeKey = "role:" + role.toLowerCase();
                Map<String, Object> props = new HashMap<>();
                props.put("role", role);
                props.putAll(spelProps);
                props.put("annotation", annotName);
                props.put("context", context);
                // @PreAuthorize 里的 hasAuthority/hasPermission 与 hasRole 同源 → 该 Role 被授予这些权限
                props.put("permissions", new ArrayList<>(permissions));

                NodeExtractionResult result = NodeExtractionResult.builder()
                        .nodeType("Role")
                        .nodeKey(nodeKey)
                        .displayName(role)
                        .description("RBAC 角色: " + role + " (@" + annotName + " on " + context + ")")
                        .sourcePath(filePath)
                        .sourceType("CODE_AST")
                        .confidence(0.95)
                        .properties(props)
                        .build();
                results.add(result);
            }

            // 创建 Permission 节点（nodeKey = 权限标识小写化，与前端统一）
            for (String perm : permissions) {
                String permKey = perm.toLowerCase();
                Map<String, Object> permProps = new HashMap<>();
                permProps.put("permission", perm);
                permProps.putAll(spelProps);
                permProps.put("annotation", annotName);
                permProps.put("context", context);

                NodeExtractionResult permResult = NodeExtractionResult.builder()
                        .nodeType("Permission")
                        .nodeKey(permKey)
                        .displayName(perm)
                        .description("RBAC 权限: " + perm + " (@" + annotName + " on " + context + ")")
                        .sourcePath(filePath)
                        .sourceType("CODE_AST")
                        .confidence(0.95)
                        .properties(permProps)
                        .build();
                results.add(permResult);
            }
        }
    }

    /**
     * 解析 RBAC 注解，分离出 roles 与 permissions。
     * <p>@PreAuthorize SpEL：hasRole/hasAnyRole → Role；hasAuthority/hasAnyAuthority/hasPermission → Permission。
     * 其他注解（@RequiresRoles/@Secured/@RolesAllowed）直接取值作为 Role。</p>
     */
    private void parseRbacValues(AnnotationExpr annotation, String annotName,
                                   Set<String> roles, Set<String> permissions,
                                   Map<String, Object> props) {
        String value = extractAnnotationValue(annotation);
        if (value == null || value.isEmpty()) return;

        if ("PreAuthorize".equals(annotName)) {
            // 保留原始 SpEL 表达式，记录逻辑关系（and/or/not）
            props.put("spelExpression", value);
            // 简单判断逻辑关系
            if (value.contains(" or ")) {
                props.put("logicRelation", "OR");
            } else if (value.contains(" and ")) {
                props.put("logicRelation", "AND");
            } else {
                props.put("logicRelation", "SINGLE");
            }

            // hasRole / hasAnyRole → Role
            Matcher rm = HAS_ROLE_PATTERN.matcher(value);
            while (rm.find()) {
                String role = rm.group(1).trim();
                if (!role.isEmpty()) roles.add(stripRolePrefix(role));
            }
            // hasAuthority / hasAnyAuthority / hasPermission → Permission
            Matcher pm = HAS_AUTHORITY_PATTERN.matcher(value);
            while (pm.find()) {
                String perm = pm.group(1).trim();
                if (!perm.isEmpty()) permissions.add(perm);
            }
        } else {
            // @RequiresRoles / @Secured / @RolesAllowed — 直接值或数组
            String cleaned = value.replace("{", "").replace("}", "")
                    .replace("\"", "").replace("'", "");
            for (String part : cleaned.split(",")) {
                String role = part.trim();
                if (!role.isEmpty()) roles.add(stripRolePrefix(role));
            }
        }
    }

    /** 提取注解成员值文本（兼容 SingleMember / Normal 两种写法）。 */
    private String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
        } else if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String name = pair.getNameAsString();
                if ("value".equals(name) || "roles".equals(name)) {
                    return pair.getValue().toString();
                }
            }
        }
        return "";
    }

    /** 去除 ROLE_ 前缀（@Secured("ROLE_ADMIN") → ADMIN）。 */
    private String stripRolePrefix(String role) {
        if (role.startsWith("ROLE_")) {
            return role.substring(5);
        }
        return role;
    }
}
