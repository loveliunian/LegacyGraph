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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java source structure extractor.
 */
@Slf4j
public class JavaStructureExtractor {

    private final JavaParser javaParser;

    public JavaStructureExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    public List<JavaClassInfo> extractFromFile(Path javaFile) throws IOException {
        List<JavaClassInfo> classes = new ArrayList<>();
        ParseResult<CompilationUnit> result = javaParser.parse(javaFile);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            result = javaParser.parse(javaFile);
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse Java structure (after retry): {}", javaFile);
            log.warn("Parse problems: {}", result.getProblems());
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
                classInfo.getMethods().add(new JavaMethodInfo(
                        methodName,
                        qualifiedName + "." + methodName,
                        method.getBegin().map(p -> p.line).orElse(null),
                        method.getEnd().map(p -> p.line).orElse(null)
                ));
            }
            classes.add(classInfo);
        }

        return classes;
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
