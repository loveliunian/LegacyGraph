package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事务与并发扫描器（v6.0 P9：CONCURRENCY）— 扫描 Java 源码中的事务与并发特征。
 * <p>覆盖以下维度：
 * <ul>
 *   <li>{@code @Transactional} 注解的 propagation（传播行为）与 isolation（隔离级别）属性</li>
 *   <li>{@code @Async} 注解（异步方法）</li>
 *   <li>{@code synchronized} 关键字、{@code ReentrantLock} 类、{@code @Lock} 注解（锁机制）</li>
 *   <li>self-invocation 自调用检测：同类内方法 A 调用方法 B（B 有 @Transactional），标记 txFailureRisk</li>
 * </ul>
 * 抽取结果用于将事务/并发属性写入 Method 节点 properties，并构建
 * TransactionScope 节点及 Method --BOUND_BY--> TransactionScope 边。
 * </p>
 */
@Slf4j
@Component
public class ConcurrencyExtractor {

    private final JavaParser javaParser;

    /** 锁类型常量 */
    public static final String LOCK_SYNCHRONIZED = "SYNCHRONIZED";
    public static final String LOCK_REENTRANT_LOCK = "REENTRANT_LOCK";
    public static final String LOCK_JPA_LOCK = "JPA_LOCK";

    /** @Transactional 注解的合法传播行为 */
    private static final Set<String> PROPAGATION_VALUES = Set.of(
            "REQUIRED", "REQUIRES_NEW", "NESTED", "SUPPORTS",
            "NOT_SUPPORTED", "NEVER", "MANDATORY");

    /** @Transactional 注解的合法隔离级别 */
    private static final Set<String> ISOLATION_VALUES = Set.of(
            "DEFAULT", "READ_UNCOMMITTED", "READ_COMMITTED",
            "REPEATABLE_READ", "SERIALIZABLE");

    public ConcurrencyExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取事务与并发事实。
     *
     * @param javaFile Java 源文件路径
     * @return 扫描结果（含 ConcurrencyFact 列表）
     */
    public ConcurrencyScanResult extractFromFile(Path javaFile) throws IOException {
        ConcurrencyScanResult result = new ConcurrencyScanResult();
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
        String qualifiedClassName = packageName.isEmpty() ? className : packageName + "." + className;

        for (ClassOrInterfaceDeclaration typeDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (typeDecl.isInterface()) {
                continue;
            }
            String declQualifiedName = typeDecl.getFullyQualifiedName().orElse(qualifiedClassName);
            scanClass(typeDecl, declQualifiedName, javaFile, result);
        }

        return result;
    }

    /**
     * 扫描单个类的事务与并发特征。
     * <p>第一遍收集 @Transactional 方法集合（用于 self-invocation 检测），
     * 第二遍逐方法扫描并检测自调用风险。</p>
     */
    private void scanClass(ClassOrInterfaceDeclaration typeDecl, String qualifiedName,
                           Path javaFile, ConcurrencyScanResult result) {
        List<MethodDeclaration> methods = typeDecl.getMethods();
        if (methods.isEmpty()) {
            return;
        }

        // 第一遍：收集 @Transactional 方法名集合（用于 self-invocation 检测）
        Set<String> transactionalMethodNames = new HashSet<>();
        for (MethodDeclaration method : methods) {
            if (hasAnnotation(method, "Transactional")) {
                transactionalMethodNames.add(method.getNameAsString());
            }
        }

        // 预扫描：检测类内 ReentrantLock 字段（用于 lockType 判断）
        Set<String> reentrantLockFieldNames = collectReentrantLockFields(typeDecl);

        // 第二遍：逐方法扫描
        for (MethodDeclaration method : methods) {
            ConcurrencyFact fact = scanMethod(method, qualifiedName, javaFile,
                    transactionalMethodNames, reentrantLockFieldNames);
            if (fact != null) {
                result.getFacts().add(fact);
            }
        }
    }

    /**
     * 扫描单个方法的事务与并发特征，构建 ConcurrencyFact。
     *
     * @return 有事务/并发特征时返回 ConcurrencyFact，否则返回 null
     */
    private ConcurrencyFact scanMethod(MethodDeclaration method, String qualifiedName, Path javaFile,
                                       Set<String> transactionalMethodNames,
                                       Set<String> reentrantLockFieldNames) {
        String methodName = method.getNameAsString();
        String methodSignature = MethodSignatureSupport.build(method);

        // 解析 @Transactional
        boolean transactional = false;
        String propagation = null;
        String isolation = null;
        AnnotationExpr txAnnotation = findAnnotation(method, "Transactional");
        if (txAnnotation != null) {
            transactional = true;
            propagation = parseAnnotationMember(txAnnotation, "propagation", PROPAGATION_VALUES);
            isolation = parseAnnotationMember(txAnnotation, "isolation", ISOLATION_VALUES);
        }

        // 解析 @Async
        boolean async = hasAnnotation(method, "Async");

        // 检测锁机制
        String lockType = detectLockType(method, reentrantLockFieldNames);

        // self-invocation 检测：本方法是否调用同类内的 @Transactional 方法
        // （若调用，则被调 @Transactional 方法的代理会失效 → 标记风险在 caller 上）
        String selfInvocationTarget = detectSelfInvocation(method, transactionalMethodNames);

        // 无任何并发特征且无 self-invocation → 跳过
        if (!transactional && !async && lockType == null && selfInvocationTarget == null) {
            return null;
        }

        ConcurrencyFact fact = new ConcurrencyFact();
        fact.setMethodName(methodName);
        fact.setMethodSignature(methodSignature);
        fact.setClassName(qualifiedName);
        fact.setTransactional(transactional);
        fact.setPropagation(propagation);
        fact.setIsolation(isolation);
        fact.setAsync(async);
        fact.setLockType(lockType);
        fact.setSourcePath(javaFile.toString());
        fact.setStartLine(method.getBegin().map(p -> p.line).orElse(null));
        fact.setEndLine(method.getEnd().map(p -> p.line).orElse(null));

        // self-invocation 风险：本方法（caller）通过自调用触发了 @Transactional 方法，
        // 导致被调方法事务失效。风险标记在 caller 上，并记录被调方法名。
        if (selfInvocationTarget != null) {
            fact.setTxFailureRisk(true);
            fact.setTxFailureReason("self-invocation: " + methodName + " 同类内直接调用 @Transactional 方法 "
                    + selfInvocationTarget + "，事务代理失效");
        }

        return fact;
    }

    /**
     * 检测方法的锁机制类型。
     * <p>优先级：synchronized 方法/块 > ReentrantLock 调用 > @Lock 注解</p>
     *
     * @return 锁类型字符串（多个用逗号分隔），无锁时返回 null
     */
    private String detectLockType(MethodDeclaration method, Set<String> reentrantLockFieldNames) {
        Set<String> detected = new HashSet<>();

        // 1. synchronized 方法（方法声明上的 synchronized 修饰符）
        boolean synchronizedMethod = method.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.SYNCHRONIZED);
        // synchronized 块（方法体内含 SynchronizedStmt）
        boolean synchronizedBlock = !method.findAll(com.github.javaparser.ast.stmt.SynchronizedStmt.class).isEmpty();
        if (synchronizedMethod || synchronizedBlock) {
            detected.add(LOCK_SYNCHRONIZED);
        }

        // 2. ReentrantLock：检测 .lock() / .unlock() / .tryLock() 调用，且 scope 是 ReentrantLock 字段
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String callName = call.getNameAsString();
            if (!"lock".equals(callName) && !"unlock".equals(callName) && !"tryLock".equals(callName)) {
                continue;
            }
            var scopeOpt = call.getScope();
            if (scopeOpt.isEmpty()) {
                continue;
            }
            Expression scope = scopeOpt.get();
            String scopeName = null;
            if (scope instanceof NameExpr ne) {
                scopeName = ne.getNameAsString();
            } else if (scope instanceof FieldAccessExpr fae) {
                scopeName = fae.getNameAsString();
            }
            if (scopeName != null && reentrantLockFieldNames.contains(scopeName)) {
                detected.add(LOCK_REENTRANT_LOCK);
                break;
            }
        }

        // 3. @Lock 注解（javax.persistence.Lock / 自定义 Lock 注解）
        if (hasAnnotation(method, "Lock")) {
            detected.add(LOCK_JPA_LOCK);
        }

        return detected.isEmpty() ? null : String.join(",", detected);
    }

    /**
     * 收集类内 ReentrantLock 类型字段名集合（用于判断 .lock() 调用是否作用于 ReentrantLock）。
     */
    private Set<String> collectReentrantLockFields(ClassOrInterfaceDeclaration typeDecl) {
        Set<String> fieldNames = new HashSet<>();
        for (FieldDeclaration field : typeDecl.getFields()) {
            for (VariableDeclarator vd : field.getVariables()) {
                ClassOrInterfaceType fieldType = vd.getType().isClassOrInterfaceType()
                        ? vd.getType().asClassOrInterfaceType() : null;
                if (fieldType != null && "ReentrantLock".equals(fieldType.getNameAsString())) {
                    fieldNames.add(vd.getNameAsString());
                }
            }
        }
        // 兼容类型全限定名 java.util.concurrent.locks.ReentrantLock
        for (ObjectCreationExpr oce : typeDecl.findAll(ObjectCreationExpr.class)) {
            String typeName = oce.getType().getNameAsString();
            if ("ReentrantLock".equals(typeName)) {
                // 无法直接关联到字段名，记录类型存在即可
                // （检测逻辑在 detectLockType 中通过字段名匹配，此处仅做类型收集）
            }
        }
        return fieldNames;
    }

    /**
     * 检测 self-invocation：方法体内是否存在对同类内 @Transactional 方法的直接调用。
     * <p>self-invocation 指同类内方法 A 直接调用方法 B（B 标注 @Transactional），
     * 由于 Spring AOP 基于代理，同类内部调用不经过代理，导致 B 的 @Transactional 失效。</p>
     *
     * @return 被调用的 @Transactional 方法名（首个命中），无则返回 null
     */
    private String detectSelfInvocation(MethodDeclaration caller, Set<String> transactionalMethodNames) {
        if (transactionalMethodNames.isEmpty()) {
            return null;
        }
        String callerName = caller.getNameAsString();
        for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
            // 仅检测无 scope 或 scope 为 this 的调用（同类内部调用）
            var scopeOpt = call.getScope();
            if (scopeOpt.isPresent()) {
                Expression scope = scopeOpt.get();
                if (scope instanceof NameExpr ne && !"this".equals(ne.getNameAsString())) {
                    // scope 是其他对象 → 非自调用
                    continue;
                }
                if (scope instanceof FieldAccessExpr) {
                    // scope 是字段访问 → 非自调用
                    continue;
                }
                // this.xxx() 调用 → 仍是自调用，继续
            }
            // 无 scope（裸方法调用）→ 自调用
            String calleeName = call.getNameAsString();
            // 排除自递归（A 调用 A）
            if (calleeName.equals(callerName)) {
                continue;
            }
            if (transactionalMethodNames.contains(calleeName)) {
                return calleeName;
            }
        }
        return null;
    }

    /**
     * 判断方法是否标注了指定注解（按简单名匹配）。
     */
    private boolean hasAnnotation(MethodDeclaration method, String annotationName) {
        return method.getAnnotations().stream()
                .anyMatch(a -> annotationName.equals(a.getNameAsString()));
    }

    /**
     * 查找方法上的指定注解（按简单名匹配）。
     */
    private AnnotationExpr findAnnotation(MethodDeclaration method, String annotationName) {
        return method.getAnnotations().stream()
                .filter(a -> annotationName.equals(a.getNameAsString()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析 NormalAnnotationExpr 的成员值（如 propagation=Propagation.REQUIRES_NEW）。
     * <p>注解成员值常见形式：
     * <ul>
     *   <li>{@code Propagation.REQUIRES_NEW} — FieldAccessExpr，取字段名</li>
     *   <li>{@code "REQUIRED"} — StringLiteralExpr，取字面量</li>
     *   <li>{@code Propagation.REQUIRES_NEW} 简写为 {@code REQUIRES_NEW} — NameExpr，取名</li>
     * </ul>
     * 仅当值在 validValues 集合内时返回（过滤枚举前缀后的简单名）。</p>
     *
     * @param annotation  注解表达式
     * @param memberName  成员名（如 "propagation" / "isolation"）
     * @param validValues 合法值集合（用于校验）
     * @return 匹配的值（如 "REQUIRES_NEW"），未找到或不合法返回 null
     */
    private String parseAnnotationMember(AnnotationExpr annotation, String memberName, Set<String> validValues) {
        if (!(annotation instanceof NormalAnnotationExpr normalAnn)) {
            return null;
        }
        for (MemberValuePair pair : normalAnn.getPairs()) {
            if (!memberName.equals(pair.getNameAsString())) {
                continue;
            }
            Expression value = pair.getValue();
            String extracted = extractEnumSimpleName(value);
            if (extracted != null && validValues.contains(extracted)) {
                return extracted;
            }
        }
        return null;
    }

    /**
     * 从注解成员值表达式中提取枚举简单名。
     * <p>支持三种形式：
     * <ul>
     *   <li>{@code Propagation.REQUIRES_NEW} → "REQUIRES_NEW"（FieldAccessExpr）</li>
     *   <li>{@code REQUIRES_NEW} → "REQUIRES_NEW"（NameExpr）</li>
     *   <li>{@code "REQUIRED"} → "REQUIRED"（StringLiteralExpr）</li>
     * </ul>
     */
    private String extractEnumSimpleName(Expression expr) {
        if (expr instanceof FieldAccessExpr fae) {
            return fae.getNameAsString();
        }
        if (expr instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        if (expr instanceof com.github.javaparser.ast.expr.StringLiteralExpr sle) {
            return sle.getValue();
        }
        return null;
    }

    // ==================== 事实类 ====================

    /**
     * 事务并发事实 — 描述一个方法的事务与并发特征。
     */
    @Data
    public static class ConcurrencyFact {
        /** 方法名 */
        private String methodName;
        /** 方法签名（与 Method 节点 key 对齐） */
        private String methodSignature;
        /** 所在类全限定名 */
        private String className;
        /** 是否标注 @Transactional */
        private boolean transactional;
        /** 事务传播行为：REQUIRED/REQUIRES_NEW/NESTED/SUPPORTS/NOT_SUPPORTED/NEVER/MANDATORY */
        private String propagation;
        /** 事务隔离级别：DEFAULT/READ_UNCOMMITTED/READ_COMMITTED/REPEATABLE_READ/SERIALIZABLE */
        private String isolation;
        /** 是否标注 @Async */
        private boolean async;
        /** 锁类型：SYNCHRONIZED/REENTRANT_LOCK/JPA_LOCK（多个用逗号分隔） */
        private String lockType;
        /** 是否存在事务失效风险（self-invocation） */
        private boolean txFailureRisk;
        /** 事务失效原因描述 */
        private String txFailureReason;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }

    /**
     * 一次文件扫描的聚合结果。
     */
    @Data
    public static class ConcurrencyScanResult {
        private List<ConcurrencyFact> facts = new ArrayList<>();
    }
}
