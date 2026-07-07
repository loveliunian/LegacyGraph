package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务抽取器 — 解析 @Scheduled、@XxlJob、@ScheduledTask 等注解。
 * 抽取结果用于构建 ScheduledJob 节点。
 */
@Slf4j
@Component
public class ScheduledJobExtractor {

    private final JavaParser javaParser;

    public ScheduledJobExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取定时任务信息。
     */
    public List<ScheduledJobFact> extractFromFile(Path javaFile) throws IOException {
        List<ScheduledJobFact> result = new ArrayList<>();
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

        for (var typeDecl : cu.getTypes()) {
            typeDecl.getMethods().forEach(method -> {
                method.getAnnotations().forEach(annotation -> {
                    String annotationName = annotation.getNameAsString();
                    if (isScheduledAnnotation(annotationName)) {
                        ScheduledJobFact fact = new ScheduledJobFact();
                        fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
                        fact.setMethodName(method.getNameAsString());
                        fact.setMethodSignature(MethodSignatureSupport.build(method));
                        fact.setAnnotationType(annotationName);
                        fact.setSourcePath(javaFile.toString());
                        fact.setStartLine(method.getBegin().map(p -> p.line).orElse(null));
                        fact.setEndLine(method.getEnd().map(p -> p.line).orElse(null));

                        // 提取 cron/fixedRate/fixedDelay 等参数
                        extractScheduleParams(annotation, fact);
                        result.add(fact);
                    }
                });
            });
        }

        return result;
    }

    private boolean isScheduledAnnotation(String name) {
        return "Scheduled".equals(name) 
            || "XxlJob".equals(name) 
            || "ScheduledTask".equals(name)
            || "EnableScheduling".equals(name);
    }

    private void extractScheduleParams(AnnotationExpr annotation, ScheduledJobFact fact) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            String value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
            fact.setCronExpression(cleanValue(value));
        } else if (annotation.isNormalAnnotationExpr()) {
            for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String name = pair.getNameAsString();
                String value = pair.getValue().toString();
                switch (name) {
                    case "cron" -> fact.setCronExpression(cleanValue(value));
                    case "fixedRate" -> fact.setFixedRate(cleanValue(value));
                    case "fixedDelay" -> fact.setFixedDelay(cleanValue(value));
                    case "initialDelay" -> fact.setInitialDelay(cleanValue(value));
                    case "value" -> fact.setJobName(cleanValue(value));
                }
            }
        }
    }

    private String cleanValue(String value) {
        if (value == null) return null;
        return value.replace("\"", "").replace("'", "").trim();
    }

    /**
     * 定时任务事实。
     */
    @Data
    public static class ScheduledJobFact {
        private String className;
        private String methodName;
        private String methodSignature;
        private String annotationType;
        private String cronExpression;
        private String fixedRate;
        private String fixedDelay;
        private String initialDelay;
        private String jobName;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }
}
