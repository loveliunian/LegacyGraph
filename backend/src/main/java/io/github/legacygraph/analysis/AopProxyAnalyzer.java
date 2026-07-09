package io.github.legacygraph.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring AOP 代理分析器 — 解析切面、通知与切入点表达式。
 * 覆盖 @Aspect 类、@Around/@Before/@After/@AfterReturning/@AfterThrowing 通知，
 * 以及 @Pointcut 的 execution 表达式，抽取结果用于在图谱中补充被代理方法到通知方法的隐式调用边。
 */
@Slf4j
@Component
public class AopProxyAnalyzer {

    private final JavaParser javaParser;

    private static final Set<String> ADVICE_ANNOTATIONS = Set.of(
            "Around", "Before", "After", "AfterReturning", "AfterThrowing"
    );

    public AopProxyAnalyzer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取 AOP 切面事实。
     */
    public List<AopAspectFact> extractFromFile(Path javaFile) throws IOException {
        List<AopAspectFact> result = new ArrayList<>();
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
            // 仅处理标注 @Aspect 的类
            if (!hasAnnotation(clazz, "Aspect")) {
                continue;
            }
            String aspectClass = packageName.isEmpty()
                    ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            for (MethodDeclaration method : clazz.getMethods()) {
                // @Pointcut 定义切入点
                for (var ann : method.getAnnotations()) {
                    if ("Pointcut".equals(ann.getNameAsString())) {
                        AopAspectFact fact = new AopAspectFact();
                        fact.setAspectClass(aspectClass);
                        fact.setAdviceType("POINTCUT");
                        fact.setAdviceMethod(method.getNameAsString());
                        fact.setPointcutExpression(extractAnnotationValue(ann));
                        fact.setSourcePath(sourcePath);
                        fact.setLineNumber(ann.getBegin().map(p -> p.line).orElse(null));
                        fact.setInterceptedPattern(parseExecutionExpression(fact.getPointcutExpression()));
                        result.add(fact);
                    }
                }
                // 通知注解 @Around/@Before/...
                for (var ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (ADVICE_ANNOTATIONS.contains(annName)) {
                        AopAspectFact fact = new AopAspectFact();
                        fact.setAspectClass(aspectClass);
                        fact.setAdviceType(annName.toUpperCase());
                        fact.setAdviceMethod(method.getNameAsString());
                        fact.setPointcutExpression(extractAnnotationValue(ann));
                        fact.setSourcePath(sourcePath);
                        fact.setLineNumber(ann.getBegin().map(p -> p.line).orElse(null));
                        fact.setInterceptedPattern(parseExecutionExpression(fact.getPointcutExpression()));
                        result.add(fact);
                    }
                }
            }
        }

        return result;
    }

    private boolean hasAnnotation(ClassOrInterfaceDeclaration clazz, String annotationName) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            if (annotationName.equals(ann.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取注解的字符串值（单成员注解直接取值；normal 注解取 value/pointcut 属性）。
     */
    private String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return stripQuotes(((SingleMemberAnnotationExpr) annotation).getMemberValue().toString());
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String name = pair.getNameAsString();
                if ("value".equals(name) || "pointcut".equals(name)) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return null;
    }

    private String stripQuotes(String value) {
        if (value == null) return null;
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 解析 execution(...) 表达式，提取被拦截的类/方法模式字符串。
     * execution(修饰符? 返回类型 包名.类名.方法名(参数) 抛出异常?)
     * 这里仅原样提取 execution(...) 内部文本，供后续图谱匹配使用。
     */
    private String parseExecutionExpression(String pointcut) {
        if (pointcut == null || pointcut.isBlank()) {
            return null;
        }
        int idx = pointcut.indexOf("execution(");
        if (idx < 0) {
            return null;
        }
        int start = idx + "execution(".length();
        int depth = 1;
        int end = -1;
        for (int i = start; i < pointcut.length(); i++) {
            char c = pointcut.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end > start) {
            return pointcut.substring(start, end).trim();
        }
        return null;
    }

    /**
     * AOP 切面事实。
     */
    @Data
    public static class AopAspectFact {
        /** 切面类全限定名 */
        private String aspectClass;
        /** 通知类型：AROUND/BEFORE/AFTER/AFTER_RETURNING/AFTER_THROWING/POINTCUT */
        private String adviceType;
        /** 切入点表达式（注解原始值） */
        private String pointcutExpression;
        /** 从 execution(...) 中解析出的被拦截类/方法模式 */
        private String interceptedPattern;
        /** 通知方法名（或 @Pointcut 所在方法名） */
        private String adviceMethod;
        /** 源文件路径 */
        private String sourcePath;
        /** 注解所在行号 */
        private Integer lineNumber;
    }
}
