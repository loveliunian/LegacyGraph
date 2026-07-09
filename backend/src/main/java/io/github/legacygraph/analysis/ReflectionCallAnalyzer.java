package io.github.legacygraph.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 反射调用分析器 — 解析 Java 源码中的反射调用。
 * 覆盖 Class.forName()、Method.invoke()、getDeclaredMethod() 等动态派发点，
 * 抽取结果用于在图谱中标注运行时才能确定的调用边。
 */
@Slf4j
@Component
public class ReflectionCallAnalyzer {

    private final JavaParser javaParser;

    public ReflectionCallAnalyzer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取反射调用事实。
     */
    public List<ReflectionCallFact> extractFromFile(Path javaFile) throws IOException {
        List<ReflectionCallFact> result = new ArrayList<>();
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
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String sourcePath = javaFile.toString();

        for (var typeDecl : cu.getTypes()) {
            if (!(typeDecl instanceof ClassOrInterfaceDeclaration)) {
                continue;
            }
            ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
            String callerClass = packageName.isEmpty()
                    ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            // 遍历所有方法调用表达式，匹配反射相关 API
            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                String methodName = call.getNameAsString();
                switch (methodName) {
                    case "forName" -> handleClassForName(call, callerClass, sourcePath, result);
                    case "invoke" -> handleMethodInvoke(call, callerClass, sourcePath, result);
                    case "getDeclaredMethod", "getMethod" -> handleGetDeclaredMethod(call, callerClass, sourcePath, result);
                    default -> { /* 非反射调用，忽略 */ }
                }
            });
        }

        return result;
    }

    /**
     * 处理 Class.forName("xxx") — targetClassName 可直接从字面量参数推断。
     */
    private void handleClassForName(MethodCallExpr call, String callerClass,
                                     String sourcePath, List<ReflectionCallFact> result) {
        ReflectionCallFact fact = new ReflectionCallFact();
        fact.setCallerClass(callerClass);
        fact.setMethodName("forName");
        fact.setSourcePath(sourcePath);
        fact.setLineNumber(call.getBegin().map(p -> p.line).orElse(null));
        // 第一个参数为类名字面量时，作为 targetClassName
        if (!call.getArguments().isEmpty()) {
            Expression arg = call.getArgument(0);
            if (arg instanceof StringLiteralExpr) {
                fact.setTargetClassName(((StringLiteralExpr) arg).getValue());
            } else {
                fact.setTargetClassName(arg.toString());
            }
        }
        result.add(fact);
    }

    /**
     * 处理 Method.invoke(obj, args) — 动态方法调用，targetClassName 通常不可静态推断。
     */
    private void handleMethodInvoke(MethodCallExpr call, String callerClass,
                                     String sourcePath, List<ReflectionCallFact> result) {
        ReflectionCallFact fact = new ReflectionCallFact();
        fact.setCallerClass(callerClass);
        fact.setMethodName("invoke");
        fact.setSourcePath(sourcePath);
        fact.setLineNumber(call.getBegin().map(p -> p.line).orElse(null));
        // targetClassName 难以静态确定，留空；可由后续符号解析补充
        fact.setTargetClassName(null);
        result.add(fact);
    }

    /**
     * 处理 getDeclaredMethod("name", paramTypes) / getMethod(...) — methodName 可从字面量参数推断。
     */
    private void handleGetDeclaredMethod(MethodCallExpr call, String callerClass,
                                          String sourcePath, List<ReflectionCallFact> result) {
        ReflectionCallFact fact = new ReflectionCallFact();
        fact.setCallerClass(callerClass);
        fact.setMethodName(call.getNameAsString());
        fact.setSourcePath(sourcePath);
        fact.setLineNumber(call.getBegin().map(p -> p.line).orElse(null));
        // 第一个参数为方法名字面量时，记录到 fact.methodName 之外的字段不可行（与反射 API 名冲突），
        // 这里仍保留反射 API 名作为 methodName；被调用的目标方法名暂存于 targetClassName 不合适，
        // 故扩展记录在 invokeTargetMethod 字段。
        if (!call.getArguments().isEmpty()) {
            Expression arg = call.getArgument(0);
            if (arg instanceof StringLiteralExpr) {
                fact.setInvokeTargetMethod(((StringLiteralExpr) arg).getValue());
            } else {
                fact.setInvokeTargetMethod(arg.toString());
            }
        }
        result.add(fact);
    }

    /**
     * 反射调用事实。
     */
    @Data
    public static class ReflectionCallFact {
        /** 发起反射调用的类全限定名 */
        private String callerClass;
        /** 反射目标类名（Class.forName 字面量可推断；其余为 null） */
        private String targetClassName;
        /** 反射 API 方法名：forName / invoke / getDeclaredMethod / getMethod */
        private String methodName;
        /** getDeclaredMethod/getMethod 第一个参数指向的目标方法名（字面量可推断） */
        private String invokeTargetMethod;
        /** 源文件路径 */
        private String sourcePath;
        /** 调用所在行号 */
        private Integer lineNumber;
    }
}
