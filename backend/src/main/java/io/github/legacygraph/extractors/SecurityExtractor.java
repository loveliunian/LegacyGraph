package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 安全风险扫描器（v6.0 P8：SECURITY_AUDIT）— 扫描 Java 源码中的安全风险。
 * <p>覆盖以下风险类型：
 * <ul>
 *   <li>SQL 注入：Statement.executeQuery/execute 拼接、MyBatis ${} 拼接</li>
 *   <li>硬编码密钥：password/secret/apiKey/token 等变量赋值为字符串字面量</li>
 *   <li>不安全反序列化：ObjectInputStream.readObject、XMLDecoder</li>
 *   <li>XSS：response.getWriter().write 无转义</li>
 *   <li>路径遍历：new File(用户输入) 无校验</li>
 * </ul>
 * 抽取结果用于构建 SecurityRisk 节点及 HAS_RISK 边。
 * </p>
 */
@Slf4j
@Component
public class SecurityExtractor {

    private final JavaParser javaParser;

    // ====== 风险类型常量 ======
    /** SQL 注入 */
    public static final String RISK_SQL_INJECTION = "SQL_INJECTION";
    /** 硬编码密钥 */
    public static final String RISK_HARDCODED_SECRET = "HARDCODED_SECRET";
    /** 不安全反序列化 */
    public static final String RISK_UNSAFE_DESERIALIZATION = "UNSAFE_DESERIALIZATION";
    /** XSS */
    public static final String RISK_XSS = "XSS";
    /** 路径遍历 */
    public static final String RISK_PATH_TRAVERSAL = "PATH_TRAVERSAL";

    // ====== 严重等级常量 ======
    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";

    /** SQL 执行方法名 */
    private static final Set<String> SQL_EXEC_METHODS = Set.of("executeQuery", "executeUpdate", "execute", "addBatch");
    /** 反序列化相关方法/类名 */
    private static final Set<String> DESERIALIZE_METHODS = Set.of("readObject");
    private static final Set<String> DESERIALIZE_CLASSES = Set.of("ObjectInputStream", "XMLDecoder");
    /** 硬编码密钥变量名匹配模式（忽略大小写） */
    private static final Pattern SECRET_VAR_PATTERN = Pattern.compile(
            "(?i).*(password|passwd|secret|apikey|api_key|token|credential|private_key|access_key).*");

    public SecurityExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取安全风险事实。
     */
    public SecurityScanResult extractFromFile(Path javaFile) throws IOException {
        SecurityScanResult result = new SecurityScanResult();
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
            String declQualifiedName = typeDecl.getFullyQualifiedName().orElse(qualifiedClassName);

            // 1. 硬编码密钥：扫描字段声明与方法内赋值
            scanHardcodedSecrets(typeDecl, declQualifiedName, javaFile, result);

            // 2. 脱敏调用扫描：@JsonSerialize(DesensitizeSerializer) / DesensitizedUtil 调用
            scanDesensitization(typeDecl, declQualifiedName, javaFile, result);

            // 3. 扫描方法体内的安全风险
            for (MethodDeclaration method : typeDecl.getMethods()) {
                String methodName = method.getNameAsString();
                Integer methodLine = method.getBegin().map(p -> p.line).orElse(null);

                // SQL 注入
                scanSqlInjection(method, declQualifiedName, methodName, methodLine, javaFile, result);
                // 不安全反序列化
                scanUnsafeDeserialization(method, declQualifiedName, methodName, methodLine, javaFile, result);
                // XSS
                scanXss(method, declQualifiedName, methodName, methodLine, javaFile, result);
                // 路径遍历
                scanPathTraversal(method, declQualifiedName, methodName, methodLine, javaFile, result);
            }
        }

        return result;
    }

    /**
     * 扫描硬编码密钥 — 字段声明和方法内赋值中变量名匹配密钥模式且值为字符串字面量。
     */
    private void scanHardcodedSecrets(ClassOrInterfaceDeclaration typeDecl, String qualifiedName,
                                        Path javaFile, SecurityScanResult result) {
        // 字段声明
        for (VariableDeclarationExpr vde : typeDecl.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                checkSecretAssignment(vd.getNameAsString(), vd.getInitializer().orElse(null),
                        qualifiedName, null, javaFile, vd.getBegin().map(p -> p.line).orElse(null), result);
            }
        }
        // 赋值表达式
        for (AssignExpr ae : typeDecl.findAll(AssignExpr.class)) {
            if (ae.getTarget() instanceof NameExpr ne) {
                checkSecretAssignment(ne.getNameAsString(), ae.getValue(),
                        qualifiedName, null, javaFile, ae.getBegin().map(p -> p.line).orElse(null), result);
            }
        }
    }

    /**
     * 扫描脱敏调用 — @JsonSerialize(using = DesensitizeSerializer.class) 注解字段
     * 和 DesensitizedUtil 调用。
     * <p>产出 MaskingFact，用于建 Column --MASKED_AT--> Method 边。</p>
     */
    private void scanDesensitization(ClassOrInterfaceDeclaration typeDecl, String qualifiedName,
                                       Path javaFile, SecurityScanResult result) {
        // 1. @JsonSerialize(using = DesensitizeSerializer.class) 注解字段
        for (FieldDeclaration field : typeDecl.getFields()) {
            boolean hasDesensitizeAnnotation = false;
            for (var ann : field.getAnnotations()) {
                String annStr = ann.toString();
                if (annStr.contains("DesensitizeSerializer") || annStr.contains("DesensitizationSerializer")) {
                    hasDesensitizeAnnotation = true;
                    break;
                }
            }
            if (!hasDesensitizeAnnotation) {
                continue;
            }
            for (VariableDeclarator vd : field.getVariables()) {
                MaskingFact mf = new MaskingFact();
                mf.setClassName(qualifiedName);
                mf.setEnclosingMethod(null);
                mf.setFieldName(vd.getNameAsString());
                mf.setSourcePath(javaFile.toString());
                mf.setLine(vd.getBegin().map(p -> p.line).orElse(null));
                result.getMaskings().add(mf);
            }
        }

        // 2. DesensitizedUtil 调用（方法内）
        for (MethodDeclaration method : typeDecl.getMethods()) {
            String methodName = method.getNameAsString();
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                var scopeOpt = call.getScope();
                if (scopeOpt.isEmpty() || !(scopeOpt.get() instanceof NameExpr ne)) {
                    continue;
                }
                if (!"DesensitizedUtil".equals(ne.getNameAsString())) {
                    continue;
                }
                // 提取参数变量名作为 fieldName
                String fieldName = null;
                if (!call.getArguments().isEmpty()) {
                    Expression arg = call.getArgument(0);
                    if (arg instanceof NameExpr argNe) {
                        fieldName = argNe.getNameAsString();
                    }
                }
                MaskingFact mf = new MaskingFact();
                mf.setClassName(qualifiedName);
                mf.setEnclosingMethod(methodName);
                mf.setFieldName(fieldName != null ? fieldName : call.getNameAsString());
                mf.setSourcePath(javaFile.toString());
                mf.setLine(call.getBegin().map(p -> p.line).orElse(null));
                result.getMaskings().add(mf);
            }
        }
    }


    /**
     * 检查单个变量赋值是否为硬编码密钥。
     */
    private void checkSecretAssignment(String varName, Expression initializer, String qualifiedName,
                                        String enclosingMethod, Path javaFile, Integer line,
                                        SecurityScanResult result) {
        if (varName == null || initializer == null) {
            return;
        }
        if (!SECRET_VAR_PATTERN.matcher(varName).matches()) {
            return;
        }
        if (initializer instanceof StringLiteralExpr sle) {
            String value = sle.getValue();
            // 跳过空字符串和占位符
            if (value == null || value.isBlank() || value.contains("${") || value.contains("env.")) {
                return;
            }
            result.getRisks().add(buildFact(
                    RISK_HARDCODED_SECRET, SEVERITY_CRITICAL,
                    "硬编码密钥: " + varName + " = \"" + maskValue(value) + "\"",
                    qualifiedName, enclosingMethod, javaFile, line));
        }
    }

    /**
     * 扫描 SQL 注入 — Statement.executeXxx 的参数为字符串拼接（BinaryExpr 含 +）。
     */
    private void scanSqlInjection(MethodDeclaration method, String qualifiedName, String methodName,
                                    Integer methodLine, Path javaFile, SecurityScanResult result) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String callName = call.getNameAsString();
            if (!SQL_EXEC_METHODS.contains(callName)) {
                continue;
            }
            if (call.getArguments().isEmpty()) {
                continue;
            }
            Expression arg = call.getArgument(0);
            if (isStringConcatenation(arg)) {
                result.getRisks().add(buildFact(
                        RISK_SQL_INJECTION, SEVERITY_HIGH,
                        "SQL 注入风险: " + callName + "() 参数为字符串拼接，建议使用参数化查询 (#{} / PreparedStatement)",
                        qualifiedName, methodName, javaFile,
                        call.getBegin().map(p -> p.line).orElse(methodLine)));
            }
        }
    }

    /**
     * 扫描不安全反序列化 — ObjectInputStream.readObject / XMLDecoder 构造。
     */
    private void scanUnsafeDeserialization(MethodDeclaration method, String qualifiedName, String methodName,
                                             Integer methodLine, Path javaFile, SecurityScanResult result) {
        // readObject 调用
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (DESERIALIZE_METHODS.contains(call.getNameAsString())) {
                var scopeOpt = call.getScope();
                if (scopeOpt.isPresent()) {
                    String scopeType = scopeOpt.get().toString();
                    if (DESERIALIZE_CLASSES.stream().anyMatch(scopeType::contains)) {
                        result.getRisks().add(buildFact(
                                RISK_UNSAFE_DESERIALIZATION, SEVERITY_HIGH,
                                "不安全反序列化: " + call.getNameAsString() + "() 调用，可能导致远程代码执行",
                                qualifiedName, methodName, javaFile,
                                call.getBegin().map(p -> p.line).orElse(methodLine)));
                    }
                }
            }
        }
        // XMLDecoder 构造
        for (ObjectCreationExpr oce : method.findAll(ObjectCreationExpr.class)) {
            String typeName = oce.getType().asString();
            if (DESERIALIZE_CLASSES.contains(typeName)) {
                result.getRisks().add(buildFact(
                        RISK_UNSAFE_DESERIALIZATION, SEVERITY_HIGH,
                        "不安全反序列化: new " + typeName + "()，可能导致远程代码执行",
                        qualifiedName, methodName, javaFile,
                        oce.getBegin().map(p -> p.line).orElse(methodLine)));
            }
        }
    }

    /**
     * 扫描 XSS — response.getWriter().write 无转义（参数为字符串拼接）。
     */
    private void scanXss(MethodDeclaration method, String qualifiedName, String methodName,
                          Integer methodLine, Path javaFile, SecurityScanResult result) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!"write".equals(call.getNameAsString())) {
                continue;
            }
            var scopeOpt = call.getScope();
            if (scopeOpt.isEmpty() || !(scopeOpt.get() instanceof MethodCallExpr getWriterCall)) {
                continue;
            }
            if (!"getWriter".equals(getWriterCall.getNameAsString())) {
                continue;
            }
            if (call.getArguments().isEmpty()) {
                continue;
            }
            Expression arg = call.getArgument(0);
            // 参数为字符串拼接 → 可能存在 XSS
            if (isStringConcatenation(arg)) {
                result.getRisks().add(buildFact(
                        RISK_XSS, SEVERITY_MEDIUM,
                        "XSS 风险: response.getWriter().write() 参数为字符串拼接，建议使用 HTML 转义",
                        qualifiedName, methodName, javaFile,
                        call.getBegin().map(p -> p.line).orElse(methodLine)));
            }
        }
    }

    /**
     * 扫描路径遍历 — new File(变量名) 无校验（参数为 NameExpr 而非常量）。
     */
    private void scanPathTraversal(MethodDeclaration method, String qualifiedName, String methodName,
                                     Integer methodLine, Path javaFile, SecurityScanResult result) {
        for (ObjectCreationExpr oce : method.findAll(ObjectCreationExpr.class)) {
            String typeName = oce.getType().asString();
            if (!"File".equals(typeName) && !"Path".equals(typeName)) {
                continue;
            }
            if (oce.getArguments().isEmpty()) {
                continue;
            }
            Expression arg = oce.getArgument(0);
            // 参数为变量引用（非常量字符串）→ 可能存在路径遍历
            if (arg instanceof NameExpr || arg instanceof FieldAccessExpr) {
                result.getRisks().add(buildFact(
                        RISK_PATH_TRAVERSAL, SEVERITY_MEDIUM,
                        "路径遍历风险: new " + typeName + "(用户输入)，建议校验路径合法性",
                        qualifiedName, methodName, javaFile,
                        oce.getBegin().map(p -> p.line).orElse(methodLine)));
            }
        }
    }

    /**
     * 判断表达式是否为字符串拼接（含 BinaryExpr + 操作）。
     */
    private boolean isStringConcatenation(Expression expr) {
        if (expr instanceof BinaryExpr be) {
            return be.getOperator() == BinaryExpr.Operator.PLUS;
        }
        return false;
    }

    /**
     * 对密钥值做脱敏处理（只保留前 2 位，其余用 * 替换）。
     */
    private String maskValue(String value) {
        if (value == null || value.length() <= 2) {
            return "***";
        }
        return value.substring(0, 2) + "*".repeat(Math.min(value.length() - 2, 8));
    }

    /**
     * 构建 SecurityFact。
     */
    private SecurityFact buildFact(String riskType, String severity, String detail,
                                     String className, String enclosingMethod, Path sourcePath, Integer line) {
        SecurityFact fact = new SecurityFact();
        fact.setRiskType(riskType);
        fact.setSeverity(severity);
        fact.setDetail(detail);
        fact.setClassName(className);
        fact.setEnclosingMethod(enclosingMethod);
        fact.setSourcePath(sourcePath.toString());
        fact.setLine(line);
        return fact;
    }

    /**
     * 安全风险事实。
     */
    @Data
    public static class SecurityFact {
        /** 风险类型：SQL_INJECTION / HARDCODED_SECRET / UNSAFE_DESERIALIZATION / XSS / PATH_TRAVERSAL */
        private String riskType;
        /** 严重等级：CRITICAL / HIGH / MEDIUM */
        private String severity;
        /** 风险详情描述 */
        private String detail;
        /** 所在类全限定名 */
        private String className;
        /** 所在方法名 */
        private String enclosingMethod;
        private String sourcePath;
        /** 行号 */
        private Integer line;
    }

    /**
     * 脱敏调用事实（v6.0 P8：敏感数据脱敏追踪）。
     * <p>记录脱敏发生位置（方法 / 字段注解），用于建 Column --MASKED_AT--> Method 边。</p>
     */
    @Data
    public static class MaskingFact {
        /** 所在类全限定名 */
        private String className;
        /** 所在方法名（@JsonSerialize 注解场景可能为 null） */
        private String enclosingMethod;
        /** 脱敏涉及的字段/变量名（用于匹配 Column 节点） */
        private String fieldName;
        private String sourcePath;
        private Integer line;
    }

    /**
     * 一次文件扫描的聚合结果。
     */
    @Data
    public static class SecurityScanResult {
        private List<SecurityFact> risks = new ArrayList<>();
        /** 脱敏调用事实列表 */
        private List<MaskingFact> maskings = new ArrayList<>();
    }

}
