package io.github.legacygraph.analysis;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 配置连接分析器 — 解析 application.yml/application.properties 中的配置项，
 * 关联到图谱中的 ConfigItem 节点。
 * <p>
 * 配置文件本身不携带 @Value 注解，{@code referencedBean} 字段留空，
 * 由后续步骤通过匹配 Java 源码中的 @Value("${key}") 引用回填。
 */
@Slf4j
@Component
public class ConfigLinkAnalyzer {

    private final Yaml yaml = new Yaml();

    /**
     * 从配置文件抽取配置项事实，自动识别 .yml 与 .properties 格式。
     */
    public List<ConfigLinkFact> extractFromFile(Path configFile) throws IOException {
        List<ConfigLinkFact> result = new ArrayList<>();
        if (!Files.exists(configFile) || !Files.isReadable(configFile)) {
            return result;
        }

        String fileName = configFile.getFileName().toString().toLowerCase();
        String sourcePath = configFile.toString();

        try {
            if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                parseYaml(configFile, sourcePath, result);
            } else if (fileName.endsWith(".properties")) {
                parseProperties(configFile, sourcePath, result);
            } else {
                log.debug("Unsupported config file format, skip: {}", configFile);
            }
        } catch (Exception e) {
            log.warn("Failed to parse config file {}: {}", configFile, e.getMessage());
        }

        return result;
    }

    /**
     * 解析 YAML 配置，递归展平为点分 key。
     */
    private void parseYaml(Path yamlFile, String sourcePath, List<ConfigLinkFact> result) throws IOException {
        Map<String, Object> config;
        try (var reader = Files.newBufferedReader(yamlFile)) {
            config = yaml.load(reader);
        }
        if (config != null) {
            flattenYaml("", config, sourcePath, result);
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenYaml(String prefix, Map<String, Object> config,
                             String sourcePath, List<ConfigLinkFact> result) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenYaml(key, (Map<String, Object>) value, sourcePath, result);
            } else {
                ConfigLinkFact fact = new ConfigLinkFact();
                fact.setConfigKey(key);
                fact.setConfigValue(value != null ? value.toString() : null);
                fact.setSourcePath(sourcePath);
                // referencedBean 需在后续步骤通过 @Value("${key}") 匹配回填
                fact.setReferencedBean(null);
                result.add(fact);
            }
        }
    }

    /**
     * 解析 .properties 配置，每行一个 key-value。
     */
    private void parseProperties(Path propsFile, String sourcePath, List<ConfigLinkFact> result) throws IOException {
        Properties props = new Properties();
        try (var input = Files.newInputStream(propsFile)) {
            props.load(input);
        }
        for (String key : props.stringPropertyNames()) {
            ConfigLinkFact fact = new ConfigLinkFact();
            fact.setConfigKey(key);
            fact.setConfigValue(props.getProperty(key));
            fact.setSourcePath(sourcePath);
            fact.setReferencedBean(null);
            result.add(fact);
        }
    }

    /**
     * 配置连接事实。
     */
    @Data
    public static class ConfigLinkFact {
        /** 配置项 key（点分路径） */
        private String configKey;
        /** 配置项值（字符串形式） */
        private String configValue;
        /** 源文件路径 */
        private String sourcePath;
        /** 引用该配置的 bean（由 @Value("${key}") 关联，配置文件本身不携带，留待回填） */
        private String referencedBean;
    }
}
