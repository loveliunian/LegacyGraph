package io.github.legacygraph.extractors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置项抽取器 — 解析 application.yml 和 @Value 注解。
 * 抽取结果用于构建 ConfigItem 节点。
 */
@Slf4j
@Component
public class ConfigItemExtractor {

    private final JavaParser javaParser;
    private final Yaml yaml = new Yaml();
    private static final Pattern VALUE_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");

    public ConfigItemExtractor() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(LanguageLevel.JAVA_26);
        this.javaParser = new JavaParser(config);
    }

    /**
     * 从 application.yml 文件抽取配置项。
     */
    public List<ConfigItemFact> extractFromYaml(Path yamlFile) throws IOException {
        List<ConfigItemFact> result = new ArrayList<>();
        if (!Files.exists(yamlFile) || !Files.isReadable(yamlFile)) {
            log.warn("YAML file not found or not readable: {}", yamlFile);
            return result;
        }

        try (var reader = Files.newBufferedReader(yamlFile)) {
            Map<String, Object> config = yaml.load(reader);
            if (config != null) {
                flattenConfig("", config, result, yamlFile.toString());
            }
        } catch (Exception e) {
            log.warn("Failed to parse YAML file {}: {}", yamlFile, e.getMessage());
        }

        log.info("Extracted {} config items from {}", result.size(), yamlFile);
        return result;
    }

    /**
     * 从 Java 文件抽取 @Value 注解的配置项。
     */
    public List<ConfigItemFact> extractFromJavaFile(Path javaFile) throws IOException {
        List<ConfigItemFact> result = new ArrayList<>();
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

        // 遍历所有字段，查找 @Value 注解
        for (var typeDecl : cu.getTypes()) {
            typeDecl.getFields().forEach(field -> {
                field.getAnnotations().forEach(annotation -> {
                    if ("Value".equals(annotation.getNameAsString())) {
                        // @Value 注解的值可能是单参数或 NormalAnnotationExpr
                        String value = null;
                        if (annotation.isSingleMemberAnnotationExpr()) {
                            value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
                        } else if (annotation.isNormalAnnotationExpr()) {
                            // 处理 @Value(value = "${...}") 形式
                            for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                                if ("value".equals(pair.getNameAsString())) {
                                    value = pair.getValue().toString();
                                    break;
                                }
                            }
                        }
                        
                        if (value != null) {
                            Matcher matcher = VALUE_PATTERN.matcher(value);
                            if (matcher.find()) {
                                ConfigItemFact fact = new ConfigItemFact();
                                fact.setKey(matcher.group(1));
                                fact.setDefaultValue(matcher.group(2));
                                fact.setSourceType("JAVA_ANNOTATION");
                                fact.setSourcePath(javaFile.toString());
                                fact.setClassName(className);
                                field.getVariables().forEach(var -> {
                                    fact.setFieldName(var.getNameAsString());
                                });
                                fact.setStartLine(annotation.getBegin().map(p -> p.line).orElse(null));
                                fact.setEndLine(annotation.getEnd().map(p -> p.line).orElse(null));
                                result.add(fact);
                            }
                        }
                    }
                });
            });
        }

        return result;
    }

    /**
     * 递归展平 YAML 配置为 key-value 对。
     */
    private void flattenConfig(String prefix, Map<String, Object> config, 
                                List<ConfigItemFact> result, String sourcePath) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                flattenConfig(key, nested, result, sourcePath);
            } else {
                ConfigItemFact fact = new ConfigItemFact();
                fact.setKey(key);
                fact.setValue(value != null ? value.toString() : null);
                fact.setSourceType("YAML_FILE");
                fact.setSourcePath(sourcePath);
                result.add(fact);
            }
        }
    }

    /**
     * 配置项事实。
     */
    @Data
    public static class ConfigItemFact {
        private String key;
        private String value;
        private String defaultValue;
        private String sourceType; // YAML_FILE 或 JAVA_ANNOTATION
        private String sourcePath;
        private String className;
        private String fieldName;
        private Integer startLine;
        private Integer endLine;
    }
}
