package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java source structure extractor.
 */
@Slf4j
@Component
public class JavaStructureExtractor {

    /** ThreadLocal JavaParser — parallelStream 安全（JavaParser 非线程安全） */
    private final ThreadLocal<JavaParser> javaParser = ThreadLocal.withInitial(() -> {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        return new JavaParser(config);
    });

    public List<JavaClassInfo> extractFromFile(Path javaFile) throws IOException {
        List<JavaClassInfo> classes = new ArrayList<>();

        // 先读入内存再解析，避免 I/O 竞争读到半截文件
        JavaParser parser = javaParser.get();
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(Files.readString(javaFile));
        } catch (RuntimeException e) {
            // JavaParser 词法分析器偶发内部崩溃（如 IndexOutOfBounds），重试一次
            log.warn("JavaParser crashed on first parse attempt (will retry): {} — {}", javaFile, e.getMessage());
            try {
                result = parser.parse(Files.readString(javaFile));
            } catch (RuntimeException e2) {
                log.warn("Failed to parse Java structure (JavaParser crash after retry): {}", javaFile);
                log.warn("Parse error: {}", e2.getMessage());
                return classes;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // 偶发 I/O 竞争导致读入不完整 → 重读源文件并重试一次
            try {
                result = parser.parse(Files.readString(javaFile));
            } catch (RuntimeException e) {
                log.warn("Failed to parse Java structure (JavaParser crash on retry): {}", javaFile);
                log.warn("Parse error: {}", e.getMessage());
                return classes;
            }
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java structure (after retry): {}", javaFile);
            logParseProblems(result);
            return classes;
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = clazz.getNameAsString();
            String qualifiedName = clazz.getFullyQualifiedName()
                    .orElse(packageName.isBlank() ? className : packageName + "." + className);
            JavaClassInfo classInfo = new JavaClassInfo(
                    className,
                    packageName,
                    qualifiedName,
                    clazz.isInterface() ? "INTERFACE" : "CLASS",
                    javaFile.toString(),
                    clazz.getBegin().map(p -> p.line).orElse(null),
                    clazz.getEnd().map(p -> p.line).orElse(null),
                    new ArrayList<>()
            );

            for (MethodDeclaration method : clazz.getMethods()) {
                String methodName = method.getNameAsString();
                // 生成参数签名: (String, int, User)
                String paramSignature = method.getParameters().stream()
                        .map(p -> {
                            String type = p.getType().asString();
                            // 简化泛型: List<String> -> List
                            int genericIdx = type.indexOf('<');
                            if (genericIdx > 0) {
                                type = type.substring(0, genericIdx);
                            }
                            // 取简单类名: java.util.List -> List
                            int dotIdx = type.lastIndexOf('.');
                            if (dotIdx > 0) {
                                type = type.substring(dotIdx + 1);
                            }
                            return type;
                        })
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                String methodSignature = methodName + "(" + paramSignature + ")";
                classInfo.getMethods().add(new JavaMethodInfo(
                        methodName,
                        qualifiedName + "." + methodSignature,
                        method.getBegin().map(p -> p.line).orElse(null),
                        method.getEnd().map(p -> p.line).orElse(null)
                ));
            }
            classes.add(classInfo);
        }

        return classes;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaClassInfo {
        private String className;
        private String packageName;
        private String qualifiedName;
        private String kind;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
        private List<JavaMethodInfo> methods = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaMethodInfo {
        private String methodName;
        private String qualifiedName;
        private Integer startLine;
        private Integer endLine;
    }
}
