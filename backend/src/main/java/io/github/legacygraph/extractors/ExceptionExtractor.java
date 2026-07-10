package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 异常与日志抽取器（P6-28 + P6-32）— 扫描 Java 文件中的：
 * <ul>
 *   <li>{@code catch} 块捕获的异常类型（CatchClause）</li>
 *   <li>{@code throw} 语句抛出的异常（ThrowStmt）</li>
 *   <li>方法 {@code throws} 声明</li>
 *   <li>{@code log.error(...)} / {@code log.warn(...)} / {@code logger.error(...)} 等日志调用（LogPoint）</li>
 * </ul>
 * 抽取结果用于构建 Exception、LogPoint 节点及 THROWS/CATCHES/LOGS 边。
 */
@Slf4j
@Component
public class ExceptionExtractor {

    private final JavaParser javaParser;

    /** 日志框架常见变量名（如 slf4j 的 Logger 字段） */
    private static final Set<String> LOGGER_VARS = Set.of("log", "logger", "LOG", "LOGGER");
    /** 视为日志点的日志级别方法名 */
    private static final Set<String> LOG_LEVEL_METHODS = Set.of("error", "warn");

    public ExceptionExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取异常与日志事实。
     */
    public ExceptionScanResult extractFromFile(Path javaFile) throws IOException {
        ExceptionScanResult result = new ExceptionScanResult();
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

        for (var typeDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String declQualifiedName = typeDecl.getFullyQualifiedName().orElse(qualifiedClassName);

            // 1. 扫描 catch 块异常类型
            for (CatchClause cc : typeDecl.findAll(CatchClause.class)) {
                String exType = cc.getParameter().getType().asString();
                ExceptionFact fact = new ExceptionFact();
                fact.setExceptionType(exType);
                fact.setKind("CATCH");
                fact.setClassName(declQualifiedName);
                fact.setSourcePath(javaFile.toString());
                fact.setStartLine(cc.getBegin().map(p -> p.line).orElse(null));
                fact.setEndLine(cc.getEnd().map(p -> p.line).orElse(null));
                fact.setEnclosingMethod(findEnclosingMethodName(cc));
                result.getExceptions().add(fact);
            }

            // 2. 扫描 throw 语句
            for (ThrowStmt ts : typeDecl.findAll(ThrowStmt.class)) {
                String exType = resolveThrowExceptionType(ts);
                ExceptionFact fact = new ExceptionFact();
                fact.setExceptionType(exType);
                fact.setKind("THROW");
                fact.setClassName(declQualifiedName);
                fact.setSourcePath(javaFile.toString());
                fact.setStartLine(ts.getBegin().map(p -> p.line).orElse(null));
                fact.setEndLine(ts.getEnd().map(p -> p.line).orElse(null));
                fact.setEnclosingMethod(findEnclosingMethodName(ts));
                result.getExceptions().add(fact);
            }

            // 3. 扫描方法 throws 声明 + 日志调用
            for (MethodDeclaration method : typeDecl.getMethods()) {
                String methodName = method.getNameAsString();
                String methodSignature = MethodSignatureSupport.build(method);
                Integer methodStart = method.getBegin().map(p -> p.line).orElse(null);
                Integer methodEnd = method.getEnd().map(p -> p.line).orElse(null);

                // throws 声明
                for (var thrown : method.getThrownExceptions()) {
                    ExceptionFact fact = new ExceptionFact();
                    fact.setExceptionType(thrown.asString());
                    fact.setKind("THROWS_DECL");
                    fact.setClassName(declQualifiedName);
                    fact.setMethodName(methodName);
                    fact.setMethodSignature(methodSignature);
                    fact.setEnclosingMethod(methodName);
                    fact.setSourcePath(javaFile.toString());
                    fact.setStartLine(methodStart);
                    fact.setEndLine(methodEnd);
                    result.getExceptions().add(fact);
                }

                // 日志调用 log.error / log.warn / logger.error / logger.warn
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    String mname = call.getNameAsString();
                    if (!LOG_LEVEL_METHODS.contains(mname)) {
                        continue;
                    }
                    var scopeOpt = call.getScope();
                    if (scopeOpt.isEmpty()) {
                        continue;
                    }
                    var scope = scopeOpt.get();
                    String loggerVar = null;
                    if (scope instanceof NameExpr ne) {
                        loggerVar = ne.getNameAsString();
                    } else if (scope instanceof FieldAccessExpr fae) {
                        loggerVar = fae.getNameAsString();
                    }
                    if (loggerVar == null || !LOGGER_VARS.contains(loggerVar)) {
                        continue;
                    }
                    LogPointFact lp = new LogPointFact();
                    lp.setLoggerVar(loggerVar);
                    lp.setLevel(mname.toUpperCase());
                    lp.setClassName(declQualifiedName);
                    lp.setMethodName(methodName);
                    lp.setMethodSignature(methodSignature);
                    lp.setSourcePath(javaFile.toString());
                    lp.setStartLine(call.getBegin().map(p -> p.line).orElse(null));
                    // 日志消息：取第一个参数的源码表示
                    if (!call.getArguments().isEmpty()) {
                        lp.setMessage(call.getArgument(0).toString());
                    }
                    result.getLogPoints().add(lp);
                }
            }
        }

        return result;
    }

    /**
     * 解析 throw 语句抛出的异常类型。
     * <p>常见形式：
     * <ul>
     *   <li>{@code throw new XxxException("msg")} — ObjectCreationExpr，取类型名</li>
     *   <li>{@code throw e;} — NameExpr，返回变量名作为兜底</li>
     * </ul>
     */
    private String resolveThrowExceptionType(ThrowStmt ts) {
        var expr = ts.getExpression();
        if (expr instanceof ObjectCreationExpr oce) {
            return oce.getType().asString();
        }
        if (expr instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        if (expr instanceof FieldAccessExpr fae) {
            return fae.getNameAsString();
        }
        return expr.toString();
    }

    /**
     * 向上遍历父节点链，找到最近的方法声明名（catch/throw 所在方法）。
     * 若位于 lambda 或匿名类中无法定位方法，返回 null。
     */
    private String findEnclosingMethodName(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node p = parent.get();
            if (p instanceof MethodDeclaration md) {
                return md.getNameAsString();
            }
            parent = p.getParentNode();
        }
        return null;
    }

    /**
     * 异常事实。
     */
    @Data
    public static class ExceptionFact {
        /** 异常类型名（简单名或变量名兜底） */
        private String exceptionType;
        /** 事实种类：CATCH / THROW / THROWS_DECL */
        private String kind;
        /** 所在类全限定名 */
        private String className;
        /** 方法名（THROWS_DECL 时为声明 throws 的方法） */
        private String methodName;
        /** 方法签名（THROWS_DECL 时填充，用于对齐 Method 节点 key） */
        private String methodSignature;
        /** 所在方法名（CATCH / THROW 时填充） */
        private String enclosingMethod;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }

    /**
     * 日志点事实（P6-32）。
     */
    @Data
    public static class LogPointFact {
        /** 日志变量名（log / logger 等） */
        private String loggerVar;
        /** 日志级别：ERROR / WARN */
        private String level;
        private String className;
        private String methodName;
        private String methodSignature;
        /** 日志消息（第一个参数的源码表示） */
        private String message;
        private String sourcePath;
        private Integer startLine;
    }

    /**
     * 一次文件扫描的聚合结果。
     */
    @Data
    public static class ExceptionScanResult {
        private List<ExceptionFact> exceptions = new ArrayList<>();
        private List<LogPointFact> logPoints = new ArrayList<>();
    }
}
