package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.legacygraph.model.UserRoleAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RBAC 用户-角色分配代码提取器 — 从 Spring Security 配置类中提取硬编码的 User-Role 关联。
 *
 * <p>扫描目标模式：
 * <ul>
 *   <li>{@code User.builder().username("admin").roles("ADMIN")} — Spring Security User.builder 链式调用</li>
 *   <li>{@code User.withUsername("admin").roles("ADMIN")} — Spring Security User.withUsername 链式调用</li>
 *   <li>{@code new User("admin", "pass", AuthorityUtils.createAuthorityList("ROLE_ADMIN"))} — 构造器方式（简化匹配）</li>
 * </ul>
 *
 * <p>与 {@link RbacRoleExtractor} 互补：后者从注解提取 Role-Permission（GRANTS 边），
 * 本类从配置代码提取 User-Role（ASSIGNED_TO 边）。</p>
 */
@Slf4j
@Component
public class RbacUserAssignmentExtractor {

    private final JavaParser javaParser;

    public RbacUserAssignmentExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件中提取 User-Role 分配关系。
     *
     * @param file Java 源文件
     * @return 用户-角色分配列表，sourceType=CODE_AST
     */
    public List<UserRoleAssignment> extract(File file) {
        List<UserRoleAssignment> results = new ArrayList<>();
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return results;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = file.getAbsolutePath();

            // 查找 User.builder().username("X").roles("Y") 和 User.withUsername("X").roles("Y") 链式调用
            cu.findAll(MethodCallExpr.class).forEach(call -> {
                String methodName = call.getNameAsString();
                if ("roles".equals(methodName) || "authorities".equals(methodName)) {
                    // 向上追溯找到链式调用的根（User.builder() 或 User.withUsername()）
                    MethodCallExpr chain = call;
                    String username = null;
                    // 沿 scope 链向上查找 username() 调用
                    while (chain.getScope().isPresent() && chain.getScope().get() instanceof MethodCallExpr) {
                        chain = (MethodCallExpr) chain.getScope().get();
                        if ("username".equals(chain.getNameAsString()) || "withUsername".equals(chain.getNameAsString())) {
                            username = extractFirstStringArg(chain);
                            break;
                        }
                    }
                    if (username != null) {
                        // 从 roles()/authorities() 参数提取角色名
                        List<String> roleNames = extractStringArgs(call);
                        for (String role : roleNames) {
                            String cleaned = stripRolePrefix(role);
                            if (!cleaned.isEmpty()) {
                                results.add(UserRoleAssignment.builder()
                                        .userName(username)
                                        .roleName(cleaned)
                                        .sourcePath(filePath)
                                        .sourceType("CODE_AST")
                                        .build());
                            }
                        }
                    }
                }
            });

        } catch (Exception e) {
            log.warn("Failed to extract user-role assignments from {}: {}", file.getName(), e.getMessage());
        }
        return results;
    }

    /** 提取方法调用的第一个字符串字面量参数 */
    private String extractFirstStringArg(MethodCallExpr call) {
        return call.getArguments().stream()
                .filter(arg -> arg instanceof StringLiteralExpr)
                .map(arg -> ((StringLiteralExpr) arg).asString())
                .findFirst()
                .orElse(null);
    }

    /** 提取方法调用的所有字符串字面量参数 */
    private List<String> extractStringArgs(MethodCallExpr call) {
        List<String> result = new ArrayList<>();
        for (var arg : call.getArguments()) {
            if (arg instanceof StringLiteralExpr) {
                result.add(((StringLiteralExpr) arg).asString());
            }
        }
        return result;
    }

    /** 去除 ROLE_ 前缀 */
    private String stripRolePrefix(String role) {
        if (role == null || role.isBlank()) return "";
        if (role.startsWith("ROLE_")) return role.substring(5);
        return role;
    }
}
