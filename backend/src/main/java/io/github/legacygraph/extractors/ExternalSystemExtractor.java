package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部系统调用抽取器 — 解析 RestTemplate、FeignClient、WebClient 等 HTTP 客户端调用。
 * 抽取结果用于构建 ExternalSystem 节点。
 */
@Slf4j
@Component
public class ExternalSystemExtractor {

    private final JavaParser javaParser;
    private static final Set<String> HTTP_METHODS = Set.of(
        "getForObject", "getForEntity", "postForObject", "postForEntity",
        "put", "delete", "exchange", "execute"
    );
    // MQ 生产端方法名（结合 scope 区分 RabbitMQ/Kafka/RocketMQ）
    private static final Set<String> MQ_PRODUCER_METHODS = Set.of("convertAndSend", "send");
    // MQ 消费端注解简单名 → clientType
    private static final String RABBIT_LISTENER = "RabbitListener";
    private static final String KAFKA_LISTENER = "KafkaListener";
    // RocketMQ 消费端注解简单名（兼容 @MessageListener 与 @RocketMQMessageListener）
    private static final Set<String> ROCKET_MQ_LISTENERS = Set.of("MessageListener", "RocketMQMessageListener");

    public ExternalSystemExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 Java 文件抽取外部系统调用信息。
     */
    public List<ExternalCallFact> extractFromFile(Path javaFile) throws IOException {
        List<ExternalCallFact> result = new ArrayList<>();
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
            // 检测 @FeignClient 注解
            typeDecl.getAnnotations().forEach(annotation -> {
                if ("FeignClient".equals(annotation.getNameAsString())) {
                    ExternalCallFact fact = new ExternalCallFact();
                    fact.setClassName(packageName.isEmpty() ? className : packageName + "." + className);
                    fact.setClientType("FeignClient");
                    fact.setProtocol("HTTP");
                    fact.setSourcePath(javaFile.toString());
                    fact.setStartLine(typeDecl.getBegin().map(p -> p.line).orElse(null));
                    fact.setEndLine(typeDecl.getEnd().map(p -> p.line).orElse(null));

                    // 提取 URL/name 参数
                    if (annotation.isSingleMemberAnnotationExpr()) {
                        fact.setServiceName(cleanValue(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString()));
                    } else if (annotation.isNormalAnnotationExpr()) {
                        for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                            String name = pair.getNameAsString();
                            String value = pair.getValue().toString();
                            switch (name) {
                                case "name", "value" -> fact.setServiceName(cleanValue(value));
                                case "url" -> fact.setBaseUrl(cleanValue(value));
                            }
                        }
                    }
                    result.add(fact);
                }
            });

            // 检测 Dubbo/RestTemplate/WebClient/MQ 调用
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) typeDecl;
                String fullClassName = packageName.isEmpty() ? className : packageName + "." + className;

                // 检测 @Reference/@DubboReference 注解的字段（Dubbo 消费端注入，注入级依赖）
                for (var field : clazz.getFields()) {
                    for (var annotation : field.getAnnotations()) {
                        String annName = annotation.getNameAsString();
                        if ("Reference".equals(annName) || "DubboReference".equals(annName)) {
                            ExternalCallFact fact = new ExternalCallFact();
                            fact.setClassName(fullClassName);
                            // methodName 为 null（注入级依赖，无具体方法调用）
                            fact.setClientType("Dubbo");
                            fact.setProtocol("Dubbo");
                            // 字段类型即 Dubbo 接口，作为 serviceName 与 destination
                            String fieldType = field.getVariable(0).getTypeAsString();
                            fact.setServiceName(fieldType);
                            fact.setDestination(fieldType);
                            fact.setSourcePath(javaFile.toString());
                            fact.setStartLine(field.getBegin().map(p -> p.line).orElse(null));
                            fact.setEndLine(field.getEnd().map(p -> p.line).orElse(null));
                            result.add(fact);
                        }
                    }
                }

                clazz.getMethods().forEach(method -> {
                    // MQ 消费端：检测 @RabbitListener/@KafkaListener/@MessageListener 注解
                    method.getAnnotations().forEach(annotation -> {
                        String annName = annotation.getNameAsString();
                        String mqType = resolveMqListenerType(annName);
                        if (mqType != null) {
                            ExternalCallFact fact = new ExternalCallFact();
                            fact.setClassName(fullClassName);
                            fact.setMethodName(method.getNameAsString());
                            fact.setMethodSignature(MethodSignatureSupport.build(method));
                            fact.setClientType(mqType);
                            fact.setProtocol("MQ");
                            fact.setSourcePath(javaFile.toString());
                            fact.setStartLine(method.getBegin().map(p -> p.line).orElse(null));
                            fact.setEndLine(method.getEnd().map(p -> p.line).orElse(null));
                            // 提取 queue/topic/exchange 名称
                            String dest = extractMqDestination(annotation);
                            if (dest != null) {
                                fact.setDestination(dest);
                                fact.setServiceName(dest);
                            }
                            result.add(fact);
                        }
                    });

                    method.findAll(MethodCallExpr.class).forEach(call -> {
                        String methodName = call.getNameAsString();
                        // RestTemplate/WebClient/HttpClient 调用
                        if (HTTP_METHODS.contains(methodName)) {
                            call.getScope().ifPresent(scope -> {
                                String scopeStr = scope.toString();
                                if (scopeStr.contains("restTemplate") || scopeStr.contains("webClient")
                                    || scopeStr.contains("httpClient")) {
                                    ExternalCallFact fact = new ExternalCallFact();
                                    fact.setClassName(fullClassName);
                                    fact.setMethodName(method.getNameAsString());
                                    fact.setMethodSignature(MethodSignatureSupport.build(method));
                                    fact.setClientType(scopeStr.contains("restTemplate") ? "RestTemplate" :
                                        (scopeStr.contains("webClient") ? "WebClient" : "HttpClient"));
                                    fact.setProtocol("HTTP");
                                    fact.setSourcePath(javaFile.toString());
                                    fact.setStartLine(call.getBegin().map(p -> p.line).orElse(null));
                                    fact.setEndLine(call.getEnd().map(p -> p.line).orElse(null));

                                    // 尝试提取 URL 参数（第一个参数通常是 URL）
                                    if (!call.getArguments().isEmpty()) {
                                        String firstArg = call.getArgument(0).toString();
                                        if (firstArg.startsWith("\"") && firstArg.endsWith("\"")) {
                                            fact.setBaseUrl(cleanValue(firstArg));
                                        }
                                    }
                                    result.add(fact);
                                }
                            });
                        }
                        // MQ 生产端：rabbitTemplate.convertAndSend / kafkaTemplate.send / rocketMQTemplate.send
                        if (MQ_PRODUCER_METHODS.contains(methodName)) {
                            call.getScope().ifPresent(scope -> {
                                String scopeStr = scope.toString();
                                String mqType = resolveMqProducerType(scopeStr);
                                if (mqType != null) {
                                    ExternalCallFact fact = new ExternalCallFact();
                                    fact.setClassName(fullClassName);
                                    fact.setMethodName(method.getNameAsString());
                                    fact.setMethodSignature(MethodSignatureSupport.build(method));
                                    fact.setClientType(mqType);
                                    fact.setProtocol("MQ");
                                    fact.setSourcePath(javaFile.toString());
                                    fact.setStartLine(call.getBegin().map(p -> p.line).orElse(null));
                                    fact.setEndLine(call.getEnd().map(p -> p.line).orElse(null));
                                    // 提取 destination（queue/topic/exchange，第一个参数）
                                    if (!call.getArguments().isEmpty()) {
                                        String firstArg = call.getArgument(0).toString();
                                        if (firstArg.startsWith("\"") && firstArg.endsWith("\"")) {
                                            String dest = cleanValue(firstArg);
                                            fact.setDestination(dest);
                                            fact.setServiceName(dest);
                                        }
                                    }
                                    result.add(fact);
                                }
                            });
                        }
                    });
                });
            }
        }

        return result;
    }

    private String cleanValue(String value) {
        if (value == null) return null;
        return value.replace("\"", "").replace("'", "").trim();
    }

    /**
     * MQ 消费端注解简单名 → clientType。
     * RabbitListener → RabbitMQ；KafkaListener → Kafka；
     * MessageListener / RocketMQMessageListener → RocketMQ。
     */
    private String resolveMqListenerType(String annotationSimpleName) {
        if (RABBIT_LISTENER.equals(annotationSimpleName)) return "RabbitMQ";
        if (KAFKA_LISTENER.equals(annotationSimpleName)) return "Kafka";
        if (ROCKET_MQ_LISTENERS.contains(annotationSimpleName)) return "RocketMQ";
        return null;
    }

    /**
     * MQ 生产端 scope → clientType。
     * rabbitTemplate → RabbitMQ；kafkaTemplate → Kafka；
     * rocketMQTemplate/rocketMqTemplate → RocketMQ。
     */
    private String resolveMqProducerType(String scopeStr) {
        if (scopeStr.contains("rabbitTemplate")) return "RabbitMQ";
        if (scopeStr.contains("kafkaTemplate")) return "Kafka";
        if (scopeStr.contains("rocketMQTemplate") || scopeStr.contains("rocketMqTemplate")) return "RocketMQ";
        return null;
    }

    /**
     * 从 MQ 消费端注解中提取 destination（queue/topic/exchange）。
     * 支持单成员注解 @XxxListener("name") 与 normal 注解的
     * queues/queue/topics/topic/queuesToDeclare 属性。
     */
    private String extractMqDestination(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return cleanValue(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString());
        } else if (annotation.isNormalAnnotationExpr()) {
            for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                String name = pair.getNameAsString();
                if ("queues".equals(name) || "queue".equals(name)
                    || "topics".equals(name) || "topic".equals(name)
                    || "queuesToDeclare".equals(name)) {
                    String value = pair.getValue().toString();
                    // queuesToDeclare 通常为 @Queue("xxx")，提取其中的字面量
                    if (value.contains("\"")) {
                        int start = value.indexOf("\"");
                        int end = value.lastIndexOf("\"");
                        if (end > start) {
                            return value.substring(start + 1, end);
                        }
                    }
                    return cleanValue(value);
                }
            }
        }
        return null;
    }

    /**
     * 外部系统调用事实。
     */
    @Data
    public static class ExternalCallFact {
        private String className;
        private String methodName;
        private String methodSignature;
        private String clientType; // FeignClient, RestTemplate, WebClient, HttpClient, Dubbo, RabbitMQ, Kafka, RocketMQ
        private String serviceName;
        private String baseUrl;
        private String protocol; // HTTP / Dubbo / MQ
        private String destination; // queue name / topic / service interface
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
    }
}
