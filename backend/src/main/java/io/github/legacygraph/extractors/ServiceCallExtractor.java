package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Service调用关系抽取器
 * 抽取Controller -> Service, Service -> Service, Service -> Mapper的调用关系
 * 支持：字段注入(@Autowired/@Inject)、构造器注入、Lombok注入
 */
@Slf4j
public class ServiceCallExtractor {

    /** 源码根目录（用于 SymbolSolver 跨文件类型解析），为 null 时退回纯 AST 推断 */
    private File sourceRoot;

    /** ThreadLocal JavaParser — parallelStream 安全（JavaParser 非线程安全） */
    private final ThreadLocal<JavaParser> javaParser = ThreadLocal.withInitial(() -> {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        // P0.1c：配置 SymbolSolver（当 sourceRoot 可用时）
        if (sourceRoot != null && sourceRoot.isDirectory()) {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                    new ReflectionTypeSolver(),
                    new JavaParserTypeSolver(sourceRoot)
            );
            config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        }
        return new JavaParser(config);
    });

    /**
     * 设置源码根目录，启用 SymbolSolver 跨文件类型解析。
     * 必须在首次调用 {@link #extractFromFile} 前设置。
     */
    public void setSourceRoot(File sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * 从Java文件抽取调用关系
     * 增强：建立注入变量名→类型映射，用于精确绑定 Controller→Service、Service→Mapper 调用。
     */
    public List<CallRelation> extractFromFile(File file) throws IOException {
        return extractFromFile(file, null);
    }

    /**
     * 从 Java 文件抽取调用关系。
     *
     * @param file           Java 源文件
     * @param cachedContent  预读的文件内容缓存（可为 null，null 时 fallback 读文件）
     */
    public List<CallRelation> extractFromFile(File file, String cachedContent) throws IOException {
        JavaParser parser = javaParser.get();
        List<CallRelation> relations = new ArrayList<>();
        String content = cachedContent != null ? cachedContent : Files.readString(file.toPath());
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(content);
        } catch (RuntimeException e) {
            // JavaParser 词法分析器偶发内部崩溃（如 IndexOutOfBounds），重试一次
            log.warn("JavaParser crashed on first parse attempt (will retry): {} — {}", file.getAbsolutePath(), e.getMessage());
            content = Files.readString(file.toPath());
            try {
                result = parser.parse(content);
            } catch (RuntimeException e2) {
                log.warn("Failed to parse Java file for service call (JavaParser crash after retry): {}", file.getAbsolutePath());
                log.warn("Parse error: {}", e2.getMessage());
                return relations;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // 偶发 I/O 竞争导致读入不完整 → 重试一次
            content = Files.readString(file.toPath());
            try {
                result = parser.parse(content);
            } catch (RuntimeException e) {
                log.warn("Failed to parse Java file for service call (JavaParser crash on retry): {}", file.getAbsolutePath());
                log.warn("Parse error: {}", e.getMessage());
                return relations;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java file for service call (after retry): {}", file.getAbsolutePath());
            logParseProblems(result);
            return relations;
        }

        CompilationUnit cu = result.getResult().get();
        String filePath = file.getAbsolutePath();
        for (var typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                String className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
                // 建立注入变量名→类型映射（字段注入 + 构造器注入）
                Map<String, String> injectedVarToType = collectInjectedVarTypes(clazz);
                Set<String> injectedServices = new HashSet<>(injectedVarToType.values());
                // S2-T3: 检测远程调用注入字段（@DubboReference/@Reference）
                Set<String> remoteInjectedVars = collectRemoteInjectedVars(clazz);

                // 遍历所有方法，抽取方法调用
                for (MethodDeclaration method : clazz.getMethods()) {
                    String methodName = method.getNameAsString();
                    // 生成参数签名，统一使用 MethodSignatureSupport
                    String callerMethod = MethodSignatureSupport.build(method);

                    // 构建方法内可见的变量→类型映射（注入字段 + 本地变量），用于推断被调用方法实参类型
                    final Map<String, String> methodVarToType =
                            collectMethodVarTypes(method, injectedVarToType);

                    method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        String calledMethod = methodCall.getNameAsString();
                        CallRelation rel = new CallRelation(className, callerMethod, calledMethod);
                        rel.setSourcePath(filePath);
                        rel.setLineNumber(methodCall.getBegin().map(p -> p.line).orElse(null));
                        rel.setCallerMethodSignature(callerMethod);  // 已包含签名

                        // 推断被调用方法签名（基于实参类型），与 JavaStructureExtractor 的 Method nodeKey 对齐
                        String calledSig = buildCalledMethodSignature(methodCall, methodVarToType);
                        if (calledSig != null) {
                            rel.setCalledMethodSignature(calledSig);
                        }

                        // 尝试解析目标：检查方法调用的 scope（如 mapper.findById → mapper 是注入的 MapperUser）
                        methodCall.getScope().ifPresent(scope -> {
                            String varName = scope.toString();
                            // 保存接收者表达式原文（P2-2），用于审计区分"未解析"vs"歧义跳过"
                            rel.setReceiverExpression(varName);
                            // 剥 "this." 前缀：this.mapper.findById() → "mapper"，否则 scope.toString()="this.mapper" 匹配不上
                            if (varName.startsWith("this.")) {
                                varName = varName.substring("this.".length());
                            }

                            // P0.1c：优先使用 SymbolSolver 解析接收者类型
                            // L-08: 保留 FQN（全限定名），不再截取简单名，提高 CALLS 边消歧准确率
                            String resolvedType = null;
                            if (sourceRoot != null) {
                                try {
                                    ResolvedType rt = scope.calculateResolvedType();
                                    if (rt != null && !rt.isNull()) {
                                        String typeName = rt.describe();
                                        // 去泛型参数
                                        int genericIdx = typeName.indexOf('<');
                                        if (genericIdx > 0) {
                                            typeName = typeName.substring(0, genericIdx);
                                        }
                                        // L-08: 保留 FQN，不截取简单名
                                        resolvedType = typeName;
                                    }
                                } catch (Exception e) {
                                    // SymbolSolver 解析失败（如类型不在源码树中），降级到文本推断
                                    log.debug("SymbolSolver failed for scope '{}' in {}: {}", varName, filePath, e.getMessage());
                                }
                            }

                            // 降级：使用方法内变量→类型映射（文本推断）
                            if (resolvedType == null) {
                                resolvedType = methodVarToType.get(varName);
                            }

                            if (resolvedType != null) {
                                rel.setTargetClass(resolvedType);
                                rel.setTargetMethod(calledMethod);
                                // S2-T3: 远程调用注入字段的方法调用标记为 REMOTE_CALL
                                if (remoteInjectedVars.contains(varName)) {
                                    rel.setEdgeTargetKind("REMOTE_CALL");
                                }
                                // P0-3: 注入的 Mapper 字段调用标记为 DATABASE_CALL，
                                // 兼容 Controller 直接 @Autowired Mapper 的项目结构（无 Service 中转）
                                else if (isMapperType(resolvedType)) {
                                    rel.setEdgeTargetKind("DATABASE_CALL");
                                }
                            }
                        });

                        // P0.1c：当 SymbolSolver 可用时，尝试解析被调用方法的声明类（处理链式调用、继承方法等）
                        // L-08: 保留 FQN，不截取简单名
                        if (sourceRoot != null && rel.getTargetClass() == null) {
                            try {
                                ResolvedMethodDeclaration rmd = methodCall.resolve();
                                String declaringType = rmd.declaringType().getQualifiedName();
                                int genericIdx = declaringType.indexOf('<');
                                if (genericIdx > 0) {
                                    declaringType = declaringType.substring(0, genericIdx);
                                }
                                // L-08: 保留 FQN，不截取简单名
                                rel.setTargetClass(declaringType);
                                rel.setTargetMethod(calledMethod);
                            } catch (Exception e) {
                                // 无法解析方法声明（如第三方库方法），跳过
                            }
                        }
                        relations.add(rel);
                    });

                    // S2-T1: 方法引用（::语法）遍历 — 补全 this::method / instance::method / ClassName::staticMethod
                    method.findAll(MethodReferenceExpr.class).forEach(methodRef -> {
                        String refMethodName = methodRef.getIdentifier();
                        CallRelation refRel = new CallRelation(className, callerMethod, refMethodName);
                        refRel.setSourcePath(filePath);
                        refRel.setLineNumber(methodRef.getBegin().map(p -> p.line).orElse(null));
                        refRel.setCallerMethodSignature(callerMethod);
                        refRel.setReceiverExpression(methodRef.toString());

                        // S2-T1: 方法引用 scope 解析 — getScope() 返回 Expression（非 Optional）
                        Expression scope = methodRef.getScope();
                        if (scope.isThisExpr()) {
                            // this::methodName → 当前类
                            refRel.setTargetClass(className);
                            refRel.setTargetMethod(refMethodName);
                        } else if (scope.isNameExpr()) {
                            String scopeName = scope.asNameExpr().getNameAsString();
                            // 优先从变量映射解析（instance::method 场景）
                            String resolvedType = methodVarToType.get(scopeName);
                            if (resolvedType != null) {
                                refRel.setTargetClass(resolvedType);
                                refRel.setTargetMethod(refMethodName);
                                // S2-T3: 远程调用注入字段标记
                                if (remoteInjectedVars.contains(scopeName)) {
                                    refRel.setEdgeTargetKind("REMOTE_CALL");
                                }
                            } else {
                                // 可能是类名引用（ClassName::staticMethod）
                                refRel.setTargetClass(scopeName);
                                refRel.setTargetMethod(refMethodName);
                            }
                        } else if (scope.isFieldAccessExpr()) {
                            // this.field::method 场景
                            String fieldScope = scope.asFieldAccessExpr().getScope().toString();
                            String fieldName = scope.asFieldAccessExpr().getNameAsString();
                            if ("this".equals(fieldScope)) {
                                String resolvedType = methodVarToType.get(fieldName);
                                if (resolvedType != null) {
                                    refRel.setTargetClass(resolvedType);
                                    refRel.setTargetMethod(refMethodName);
                                    if (remoteInjectedVars.contains(fieldName)) {
                                        refRel.setEdgeTargetKind("REMOTE_CALL");
                                    }
                                }
                            }
                        }
                        relations.add(refRel);
                    });

                    // S2-T1-PATCH: Lambda 表达式增强 — 显式遍历 LambdaExpr，
                    // 为 lambda 体内的方法调用打 LAMBDA_CALL 标记，并把 lambda 参数 + 捕获变量加入 methodVarToType，
                    // 让已抽取的 MethodCallExpr 边能正确解析 targetClass。
                    // 同时支持闭包变量（outer 局部变量）追踪：lambda 内引用了外层方法局部变量时，
                    // 把外层变量类型纳入 lambda 内的可见变量映射（lambda 闭包共享外部作用域的变量名）。
                    Map<String, String> outerLocalVars = collectOuterLocalVarTypes(method);
                    for (LambdaExpr lambda : method.findAll(LambdaExpr.class)) {
                        // lambda 参数: (x, y) -> body → 参数名加入方法变量映射
                        lambda.getParameters().forEach(p -> {
                            String paramType = normalizeTypeName(p.getType().asString());
                            if (paramType != null) {
                                methodVarToType.put(p.getNameAsString(), paramType);
                            }
                        });
                        // 捕获变量：lambda 体内引用的 NameExpr 名称若不在 methodVarToType，
                        // 尝试从外层方法局部变量解析
                        lambda.findAll(com.github.javaparser.ast.expr.NameExpr.class).forEach(nameExpr -> {
                            String name = nameExpr.getNameAsString();
                            if (!methodVarToType.containsKey(name) && outerLocalVars.containsKey(name)) {
                                methodVarToType.put(name, outerLocalVars.get(name));
                            }
                        });
                        // 为 lambda 体内的方法调用打 LAMBDA_CALL 标记（callerMethod 仍为外层方法）
                        lambda.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String calledMethod = methodCall.getNameAsString();
                            CallRelation lambdaRel = new CallRelation(className, callerMethod, calledMethod);
                            lambdaRel.setSourcePath(filePath);
                            lambdaRel.setLineNumber(methodCall.getBegin().map(p -> p.line).orElse(null));
                            lambdaRel.setCallerMethodSignature(callerMethod);
                            lambdaRel.setEdgeTargetKind("LAMBDA_CALL");
                            methodCall.getScope().ifPresent(scope -> {
                                String varName = scope.toString();
                                if (varName.startsWith("this.")) {
                                    varName = varName.substring("this.".length());
                                }
                                String resolvedType = methodVarToType.get(varName);
                                if (resolvedType != null) {
                                    lambdaRel.setTargetClass(resolvedType);
                                    lambdaRel.setTargetMethod(calledMethod);
                                    if (remoteInjectedVars.contains(varName)) {
                                        lambdaRel.setEdgeTargetKind("LAMBDA_REMOTE_CALL");
                                    }
                                    // P0-3 对齐：lambda 体里对注入 Mapper 字段的调用也标记 DATABASE_CALL，
                                    // 避免 LAMBDA_CALL 默认走 resolveCallEdgeType → CALLS 而非 CALLS_DB
                                    else if (isMapperType(resolvedType)) {
                                        lambdaRel.setEdgeTargetKind("DATABASE_CALL");
                                    }
                                }
                            });
                            // 去重
                            boolean duplicate = relations.stream().anyMatch(r ->
                                    Objects.equals(r.getLineNumber(), lambdaRel.getLineNumber())
                                            && Objects.equals(r.getTargetClass(), lambdaRel.getTargetClass())
                                            && Objects.equals(r.getCalledMethod(), lambdaRel.getCalledMethod())
                                            && "LAMBDA_CALL".equals(r.getEdgeTargetKind())
                            );
                            if (!duplicate) {
                                relations.add(lambdaRel);
                            }
                        });
                    }
                }

                // 添加注入依赖边（仅依赖关系，不含调用方法）
                for (String service : injectedServices) {
                    // 避免重复创建注入边
                    boolean alreadyAdded = relations.stream().anyMatch(r ->
                            r.getCallerClass().equals(className) && service.equals(r.getCallerMethod())
                                    && r.getTargetClass() == null);
                    if (!alreadyAdded) {
                        CallRelation depRel = new CallRelation(className, null, "injects:" + service);
                        depRel.setTargetClass(service);
                        depRel.setSourcePath(filePath);
                        relations.add(depRel);
                    }
                }
            }
        }

        return relations;
    }

    /**
     * 收集所有注入的服务依赖，返回变量名→类型名的映射
     * 支持：字段注入(@Autowired/@Inject)、构造器注入
     */
    private Map<String, String> collectInjectedVarTypes(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> varToType = new java.util.LinkedHashMap<>();

        // 处理构造器注入 — 通过解析构造参数中的类型名
        clazz.getConstructors().forEach(constructor -> {
            for (var param : constructor.getParameters()) {
                String typeName = param.getType().asString();
                if (typeName != null && !typeName.isEmpty()) {
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    varToType.put(param.getNameAsString(), typeName);
                }
            }
        });

        // 处理字段注入
        for (FieldDeclaration field : clazz.getFields()) {
            boolean hasAutowired = field.getAnnotations().stream()
                    .anyMatch(a -> {
                        String name = a.getNameAsString();
                        return "Autowired".equalsIgnoreCase(name)
                                || "Inject".equalsIgnoreCase(name)
                                || "Resource".equalsIgnoreCase(name);
                    });
            // Lombok @RequiredArgsConstructor / Spring 单构造器注入：非 static final 字段即构造器注入，
            // 源码无 @Autowired、也无显式构造器（Lombok 编译期生成），原逻辑漏掉导致 targetClass 全 null、
            // Service→Mapper 调用链断裂。final 字段在 Spring 中必然经构造器注入，等同 @Autowired 语义。
            boolean isConstructorInjectedFinal = field.isFinal() && !field.isStatic();
            if (hasAutowired || isConstructorInjectedFinal) {
                for (var variable : field.getVariables()) {
                    String varName = variable.getNameAsString();
                    String typeName = variable.getType().asString();
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    varToType.put(varName, typeName);
                }
            }
        }
        return varToType;
    }

    /**
     * S2-T3: 检测远程调用注入字段（@DubboReference / @Reference）。
     * <p>返回变量名集合，这些变量的方法调用应标记为 edgeTargetKind="REMOTE_CALL"，
     * 由 GraphBuilder.resolveCallEdgeType 路由为 CALLS_EXTERNAL 边。</p>
     */
    private Set<String> collectRemoteInjectedVars(ClassOrInterfaceDeclaration clazz) {
        Set<String> remoteVars = new HashSet<>();
        for (FieldDeclaration field : clazz.getFields()) {
            boolean isRemote = field.getAnnotations().stream()
                    .anyMatch(a -> {
                        String name = a.getNameAsString();
                        return "DubboReference".equals(name) || "Reference".equals(name);
                    });
            if (isRemote) {
                for (VariableDeclarator var : field.getVariables()) {
                    remoteVars.add(var.getNameAsString());
                }
            }
        }
        return remoteVars;
    }

    /**
     * 构建方法内可见的变量→类型映射：注入字段/构造参数（类级）+ 方法参数 + 方法内本地变量。
     * 用于推断方法调用实参的类型，从而生成被调用方法签名。
     */
    private Map<String, String> collectMethodVarTypes(MethodDeclaration method,
                                                       Map<String, String> injectedVarToType) {
        Map<String, String> varToType = new HashMap<>(injectedVarToType);
        // 方法形参（最常见的实参来源，如 mapper.findById(id) 中的 id）
        for (Parameter p : method.getParameters()) {
            varToType.put(p.getNameAsString(), normalizeTypeName(p.getType().asString()));
        }
        // 方法内本地变量
        for (VariableDeclarationExpr vde : method.findAll(VariableDeclarationExpr.class)) {
            String type = normalizeTypeName(vde.getElementType().asString());
            for (VariableDeclarator v : vde.getVariables()) {
                varToType.put(v.getNameAsString(), type);
            }
        }
        return varToType;
    }

    /**
     * S2-T1-PATCH: 收集方法内的"外层"局部变量（即非 lambda 内部声明的局部变量），
     * 用于 lambda 闭包变量追踪。lambda 可以引用外层方法的局部变量，
     * 这些变量在 lambda 体内也"可见"，需要加入 methodVarToType 才能正确解析 lambda 体内的方法调用。
     *
     * <p>实现：过滤掉 lambda 体内的 VariableDeclaration（避免重复），返回其余局部变量。
     * 复杂度 O(method.size)，无嵌套遍历。</p>
     */
    private Map<String, String> collectOuterLocalVarTypes(MethodDeclaration method) {
        Map<String, String> outer = new HashMap<>();
        for (VariableDeclarationExpr vde : method.findAll(VariableDeclarationExpr.class)) {
            // 跳过 lambda 体内的变量声明（lambda 体内的变量属于 lambda 作用域，不算外层捕获）
            if (vde.findAncestor(LambdaExpr.class).isPresent()) {
                continue;
            }
            String type = normalizeTypeName(vde.getElementType().asString());
            for (VariableDeclarator v : vde.getVariables()) {
                outer.put(v.getNameAsString(), type);
            }
        }
        return outer;
    }

    /**
     * 基于调用实参推断被调用方法签名：calledMethod(argType1, argType2)。
     * 与 JavaStructureExtractor 生成的 Method nodeKey 格式对齐（简单类型名 + ", " 分隔）。
     * 仅当所有实参类型均可推断时才返回签名；任一不可推断则返回 null
     * （回退到无签名匹配，沿用既有行为，避免误匹配重载）。
     */
    private String buildCalledMethodSignature(MethodCallExpr methodCall, Map<String, String> varToType) {
        List<Expression> args = methodCall.getArguments();
        if (args == null || args.isEmpty()) {
            return methodCall.getNameAsString() + "()";
        }
        List<String> argTypes = new ArrayList<>(args.size());
        for (Expression arg : args) {
            String type = inferArgumentTypeName(arg, varToType);
            if (type == null) {
                return null;  // 实参类型无法可靠推断，不设置签名以避免误匹配重载
            }
            argTypes.add(type);
        }
        return methodCall.getNameAsString() + "(" + String.join(", ", argTypes) + ")";
    }

    /**
     * 推断实参表达式的类型名（简单名，与 JavaStructureExtractor 归一化规则一致）。
     * P0.1c：当 SymbolSolver 可用时，优先使用符号求解；否则回退到 AST 文本推断。
     */
    private String inferArgumentTypeName(Expression expr, Map<String, String> varToType) {
        // P0.1c：优先使用 SymbolSolver
        if (sourceRoot != null) {
            try {
                ResolvedType rt = expr.calculateResolvedType();
                if (rt != null && !rt.isNull() && !rt.isVoid()) {
                    String typeName = rt.describe();
                    int genericIdx = typeName.indexOf('<');
                    if (genericIdx > 0) {
                        typeName = typeName.substring(0, genericIdx);
                    }
                    int dotIdx = typeName.lastIndexOf('.');
                    if (dotIdx > 0) {
                        typeName = typeName.substring(dotIdx + 1);
                    }
                    // 过滤原始类型描述（如 java.lang.String → String 已处理）
                    return typeName;
                }
            } catch (Exception e) {
                // 降级到 AST 推断
            }
        }

        // 降级：AST 文本推断
        if (expr.isIntegerLiteralExpr()) return "int";
        if (expr.isLongLiteralExpr()) return "long";
        if (expr.isDoubleLiteralExpr()) return "double";
        if (expr.isStringLiteralExpr() || expr.isTextBlockLiteralExpr()) return "String";
        if (expr.isBooleanLiteralExpr()) return "boolean";
        if (expr.isCharLiteralExpr()) return "char";
        if (expr.isNullLiteralExpr()) return "Object";
        if (expr.isThisExpr()) return "this";
        if (expr.isNameExpr()) {
            return normalizeTypeName(varToType.get(expr.asNameExpr().getNameAsString()));
        }
        if (expr.isFieldAccessExpr()) {
            // scope 为注入字段/本地变量时取其类型（如 this.mapper、ctx.user）
            return normalizeTypeName(varToType.get(expr.asFieldAccessExpr().getScope().toString()));
        }
        if (expr.isObjectCreationExpr()) {
            return normalizeTypeName(expr.asObjectCreationExpr().getType().asString());
        }
        if (expr.isCastExpr()) {
            return normalizeTypeName(expr.asCastExpr().getType().asString());
        }
        if (expr.isEnclosedExpr()) {
            return inferArgumentTypeName(expr.asEnclosedExpr().getInner(), varToType);
        }
        // MethodCallExpr / Lambda / Conditional 等无法在不解析符号的情况下推断返回类型
        return null;
    }

    /**
     * 类型名归一化：委托给 {@link MethodSignatureSupport#normalizeTypeName}。
     */
    private String normalizeTypeName(String type) {
        return MethodSignatureSupport.normalizeTypeName(type);
    }

    /**
     * P0-3: 判断类型名是否为 Mapper（MyBatis Mapper / DAO / Repository）。
     * <p>用于在调用抽取阶段识别注入的 Mapper 字段，将对其的方法调用标记为 DATABASE_CALL，
     * 兼容 Controller 直接 @Autowired Mapper 的项目结构（无 Service 中转）。</p>
     * <p>判断依据：简单名后缀（Mapper/Dao/DAO/Repository）或 FQN 含 BaseMapper。</p>
     */
    private static boolean isMapperType(String typeName) {
        if (typeName == null || typeName.isBlank()) return false;
        String simple = typeName.contains(".")
                ? typeName.substring(typeName.lastIndexOf('.') + 1)
                : typeName;
        if (simple.endsWith("Mapper") || simple.endsWith("Dao")
                || simple.endsWith("DAO") || simple.endsWith("Repository")) {
            return true;
        }
        // MyBatis-Plus BaseMapper 子类（FQN 含 BaseMapper）
        return typeName.contains("BaseMapper");
    }

    /**
     * 安全输出解析问题概要。使用 getMessage() 仅输出一行描述，
     * 避免 getVerboseMessage() 输出超长源码上下文或 toString() 打印 stack trace。
     */
    private static void logParseProblems(ParseResult<?> result) {
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
     * 调用关系
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CallRelation {
        private final String callerClass;
        private final String callerMethod;  // 方法名（不含参数）
        private final String calledMethod;   // 被调用方法名（不含参数）
        private String targetClass;
        private String targetMethod;
        private String sourcePath;
        private Integer lineNumber;
        private String callerMethodSignature;  // 调用方完整签名 methodName(paramType1, paramType2)
        private String calledMethodSignature;  // 被调用方完整签名（如果能解析到）
        private String receiverExpression;  // P2-2：调用点接收者表达式原文（如 "mapper"、"this.service"），用于审计
        private String edgeTargetKind;  // P1-2：调用目标类型 — SERVICE_CALL / DATABASE_CALL / LOG_CALL / CONFIG_CALL / ENDPOINT_EXPOSE

        public CallRelation(String callerClass, String callerMethod, String calledMethod) {
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.calledMethod = calledMethod;
            this.targetClass = null;
            this.targetMethod = null;
            this.sourcePath = null;
            this.lineNumber = null;
            this.callerMethodSignature = null;
            this.calledMethodSignature = null;
            this.receiverExpression = null;
            this.edgeTargetKind = null;
        }
    }
}
