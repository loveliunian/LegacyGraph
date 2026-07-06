package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息队列抽取器 — 解析 @RabbitListener、@KafkaListener、@RocketMQMessageListener 等注解。
 * 抽取结果用于构建 MQConsumer 和 MQTopic 节点。
 */
@Slf4j
@Component
public class MQExtractor {

    private final JavaParser javaParser;

    public MQExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取 MQ 消费者信息。
     */
    public List<MQConsumerFact> extractFromFile(Path javaFile) throws IOException {
        List<MQConsumerFact> result = new ArrayList<>();
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
            // 类级别注解（如 @RocketMQMessageListener）
            typeDecl.getAnnotations().forEach(annotation -> {
                String annotationName = annotation.getNameAsString();
                if (isMQAnnotation(annotationName)) {
                    MQConsumerFact fact = createConsumerFact(
                        packageName, className, annotation, annotationName, javaFile.toString(),
                        typeDecl.getBegin().map(p -> p.line).orElse(null),
                        typeDecl.getEnd().map(p -> p.line).orElse(null));
                    result.add(fact);
                }
            });

            // 方法级别注解（如 @RabbitListener、@KafkaListener）
            typeDecl.getMethods().forEach(method -> {
                method.getAnnotations().forEach(annotation -> {
                    String annotationName = annotation.getNameAsString();
                    if (isMQAnnotation(annotationName)) {
                        MQConsumerFact fact = createConsumerFact(
                            packageName, className, annotation, annotationName, javaFile.toString(),
                            method.getBegin().map(p -> p.line).orElse(null),
                            method.getEnd().map(p -> p.line).orElse(null));
                        fact.setMethodName(method.getNameAsString());
                        result.add(fact);
                    }
                });
            });
        }

        return result;
    }

    private boolean isMQAnnotation(String name) {
        return "RabbitListener".equals(name) 
            || "KafkaListener".equals(name) 
            || "RocketMQMessageListener".equals(name)
            || "JmsListener".equals(name);
    }

    private MQConsumerFact createConsumerFact(String packageName, String className, 
            AnnotationExpr annotation, String annotationType, String sourcePath,
            Integer startLine, Integer endLine) {
        MQConsumerFact fact = new MQConsumerFact();
        fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
        fact.setAnnotationType(annotationType);
        fact.setSourcePath(sourcePath);
        fact.setStartLine(startLine);
        fact.setEndLine(endLine);

        // 提取 topic/queue 参数
        if (annotation.isSingleMemberAnnotationExpr()) {
            String value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
            fact.setTopic(cleanValue(value));
        } else if (annotation.isNormalAnnotationExpr()) {
            for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String name = pair.getNameAsString();
                String value = pair.getValue().toString();
                switch (name) {
                    case "queues", "topics", "destination", "value" -> fact.setTopic(cleanValue(value));
                    case "group", "consumerGroup" -> fact.setConsumerGroup(cleanValue(value));
                }
            }
        }

        return fact;
    }

    private String cleanValue(String value) {
        if (value == null) return null;
        return value.replace("\"", "").replace("'", "").replace("{", "").replace("}", "").trim();
    }

    /**
     * MQ 消费者事实。
     */
    @Data
    public static class MQConsumerFact {
        private String className;
        private String methodName;
        private String annotationType;
        private String topic;
        private String consumerGroup;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }
}
