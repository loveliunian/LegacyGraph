package io.github.legacygraph.extractors.bpmn;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 流程引擎配置读取器 — 从目标项目配置文件自动读取 BPMN 引擎 DB 连接信息。
 * <p>
 * 扫描目标项目 {@code backendDir} 下的 {@code application*.yml} /
 * {@code application*.properties},按优先级解析流程引擎数据源,识别引擎类型,
 * 处理 jasypt 加密跳过。无需用户手动配置。
 * </p>
 *
 * <h3>配置读取优先级</h3>
 * <ol>
 *   <li>{@code flowable.datasource.*} — Flowable 独立数据源</li>
 *   <li>{@code activiti.datasource.*} — Activiti 独立数据源</li>
 *   <li>{@code camunda.datasource.*} — Camunda 独立数据源</li>
 *   <li>{@code spring.datasource.*} — 复用主数据源(最常见,引擎表与业务表同库)</li>
 * </ol>
 *
 * <h3>引擎类型识别</h3>
 * <ul>
 *   <li>存在 {@code workflow.tables.*} 配置 → CUSTOM(自研引擎)</li>
 *   <li>匹配到 {@code flowable.datasource.*} → FLOWABLE</li>
 *   <li>匹配到 {@code activiti.datasource.*} → ACTIVITI</li>
 *   <li>匹配到 {@code camunda.datasource.*} → CAMUNDA</li>
 *   <li>匹配到 {@code spring.datasource.*} 但有其他引擎配置键 → 按其他键推断</li>
 *   <li>仅 {@code spring.datasource.*} → 默认 FLOWABLE(act_ 表前缀最普遍)</li>
 * </ul>
 */
@Slf4j
@Component
public class ProcessEngineConfigExtractor {

    /** jasypt 加密值格式: ENC(...) */
    private static final Pattern ENC_PATTERN = Pattern.compile("ENC\\(.*\\)");

    /** 配置文件查找的最大深度(避免扫描过深) */
    private static final int MAX_DEPTH = 6;

    /** 排除的目录片段 */
    private static final String[] EXCLUDED_DIR_FRAGMENTS = {
            "/target/", "/build/", "/node_modules/", "/.git/", "/.idea/", "/dist/"
    };

    /** 标准引擎数据源前缀,按优先级排序 */
    private static final String[] STD_DATASOURCE_PREFIXES = {
            "flowable.datasource.",
            "activiti.datasource.",
            "camunda.datasource.",
            "spring.datasource."
    };

    /**
     * 从目标项目配置文件提取流程引擎 DB 连接信息。
     *
     * @param backendDir 目标项目后端代码根目录
     * @return 连接信息;无配置或不可用时返回 null
     */
    public ProcessEngineConnectionInfo extract(String backendDir) {
        if (backendDir == null || backendDir.isBlank()) return null;
        Path root = Paths.get(backendDir);
        if (!Files.exists(root) || !Files.isDirectory(root)) return null;

        List<Path> configFiles = findConfigFiles(root);
        if (configFiles.isEmpty()) {
            log.debug("No application config files found under {}", backendDir);
            return null;
        }

        // 按优先级合并配置: application.yml/properties(基础) → application-{profile}.yml(覆盖)
        Map<String, String> flatProps = loadAndMergeConfigs(configFiles);
        if (flatProps.isEmpty()) return null;

        // 1. 识别 CUSTOM 引擎(workflow.tables.* 存在)
        boolean hasCustomTables = flatProps.keySet().stream()
                .anyMatch(k -> k.startsWith("workflow.tables."));
        if (hasCustomTables) {
            return buildCustomConnInfo(flatProps);
        }

        // 2. 按优先级查找流程引擎数据源
        for (String prefix : STD_DATASOURCE_PREFIXES) {
            String url = flatProps.get(prefix + "url");
            if (url != null && !url.isBlank()) {
                EngineType type = detectEngineType(flatProps, prefix);
                return buildStandardConnInfo(flatProps, prefix, type);
            }
        }
        log.debug("No process engine datasource found in {}", backendDir);
        return null;
    }

    /**
     * 检测引擎类型。
     *
     * @param props         扁平化配置
     * @param matchedPrefix 匹配到的数据源前缀(如 "flowable.datasource.")
     */
    private EngineType detectEngineType(Map<String, String> props, String matchedPrefix) {
        if (matchedPrefix.startsWith("flowable.")) return EngineType.FLOWABLE;
        if (matchedPrefix.startsWith("activiti.")) return EngineType.ACTIVITI;
        if (matchedPrefix.startsWith("camunda.")) return EngineType.CAMUNDA;
        // spring.datasource 复用主库:按其他配置键推断
        if (props.keySet().stream().anyMatch(k -> k.startsWith("flowable."))) return EngineType.FLOWABLE;
        if (props.keySet().stream().anyMatch(k -> k.startsWith("activiti."))) return EngineType.ACTIVITI;
        if (props.keySet().stream().anyMatch(k -> k.startsWith("camunda."))) return EngineType.CAMUNDA;
        return EngineType.FLOWABLE; // 默认 Flowable(act_ 表前缀最普遍)
    }

    /** 构建标准 BPMN 引擎连接信息(Flowable/Activiti/Camunda) */
    private ProcessEngineConnectionInfo buildStandardConnInfo(Map<String, String> props,
                                                               String prefix, EngineType type) {
        String password = props.get(prefix + "password");
        boolean encSkipped = password != null && ENC_PATTERN.matcher(password).matches();
        if (encSkipped) {
            log.warn("Process engine datasource password is jasypt-encrypted (ENC(...)), " +
                     "skipping BPMN DB scan. Consider configuring plaintext password for LegacyGraph scan. " +
                     "(prefix={})", prefix);
        }
        return ProcessEngineConnectionInfo.builder()
                .engineType(type)
                .jdbcUrl(props.get(prefix + "url"))
                .username(props.get(prefix + "username"))
                .password(encSkipped ? null : password)
                .driverClassName(props.get(prefix + "driver-class-name"))
                .tablePrefix("act_") // 标准 BPMN 引擎固定前缀
                .encryptedSkipped(encSkipped)
                .build();
    }

    /** 构建 CUSTOM 自研引擎连接信息(含表/字段映射) */
    private ProcessEngineConnectionInfo buildCustomConnInfo(Map<String, String> props) {
        String url = props.get("workflow.datasource.url");
        if (url == null || url.isBlank()) {
            // 自研引擎可能复用主库
            url = props.get("spring.datasource.url");
        }
        String username = props.getOrDefault("workflow.datasource.username",
                props.get("spring.datasource.username"));
        String password = props.getOrDefault("workflow.datasource.password",
                props.get("spring.datasource.password"));
        String driver = props.getOrDefault("workflow.datasource.driver-class-name",
                props.get("spring.datasource.driver-class-name"));

        boolean encSkipped = password != null && ENC_PATTERN.matcher(password).matches();
        if (encSkipped) {
            log.warn("Custom workflow datasource password is jasypt-encrypted, skipping DB scan.");
        }

        // 提取表名映射
        Map<String, String> tableMap = new LinkedHashMap<>();
        for (var entry : props.entrySet()) {
            if (entry.getKey().startsWith("workflow.tables.")) {
                String logical = entry.getKey().substring("workflow.tables.".length());
                tableMap.put(logical, entry.getValue());
            }
        }
        // 提取字段名映射
        Map<String, String> columnMap = new LinkedHashMap<>();
        for (var entry : props.entrySet()) {
            if (entry.getKey().startsWith("workflow.columns.")) {
                String logical = entry.getKey().substring("workflow.columns.".length());
                columnMap.put(logical, entry.getValue());
            }
        }

        return ProcessEngineConnectionInfo.builder()
                .engineType(EngineType.CUSTOM)
                .jdbcUrl(url)
                .username(username)
                .password(encSkipped ? null : password)
                .driverClassName(driver)
                .tablePrefix(null) // CUSTOM 引擎无固定前缀
                .encryptedSkipped(encSkipped)
                .customTableMapping(tableMap)
                .customColumnMapping(columnMap)
                .build();
    }

    /**
     * 查找配置文件:root 下递归找 application*.yml / application*.properties。
     * 排除 target/build/node_modules 等目录。
     */
    private List<Path> findConfigFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .filter(this::isNotExcluded)
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("Failed to walk config files under {}: {}", root, e.getMessage());
        }
        // 排序: application.yml/properties 在前, application-{profile} 在后
        files.sort(this::configFilePriority);
        return files;
    }

    /** 判断是否为 application 配置文件 */
    private boolean isConfigFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return (name.startsWith("application") || name.startsWith("bootstrap"))
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    /** 排除构建/依赖目录 */
    private boolean isNotExcluded(Path path) {
        String pathStr = path.toString().toLowerCase();
        for (String frag : EXCLUDED_DIR_FRAGMENTS) {
            if (pathStr.contains(frag)) return false;
        }
        return true;
    }

    /**
     * 配置文件优先级排序:
     * 1. application.yml/properties(基础) 优先于 application-{profile}.*
     * 2. yml 优先于 properties(若同名)
     * 3. profile 按字母序(dev < prod < test)
     */
    private int configFilePriority(Path a, Path b) {
        String na = a.getFileName().toString().toLowerCase();
        String nb = b.getFileName().toString().toLowerCase();
        // 基础配置(无 profile)在前
        boolean aBase = na.equals("application.yml") || na.equals("application.yaml")
                || na.equals("application.properties") || na.startsWith("bootstrap");
        boolean bBase = nb.equals("application.yml") || nb.equals("application.yaml")
                || nb.equals("application.properties") || nb.startsWith("bootstrap");
        if (aBase && !bBase) return -1;
        if (!aBase && bBase) return 1;
        return na.compareTo(nb);
    }

    /**
     * 加载并合并配置文件。
     * .yml 用 snakeyaml, .properties 用 Properties 类,扁平化为 key→value。
     * 后加载的覆盖先加载的(profile 覆盖基础)。
     */
    private Map<String, String> loadAndMergeConfigs(List<Path> files) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                Map<String, String> flat = loadSingleConfig(file);
                merged.putAll(flat); // 后者覆盖前者
            } catch (Exception e) {
                log.warn("Failed to load config file {}: {}", file, e.getMessage());
            }
        }
        return merged;
    }

    /** 加载单个配置文件,返回扁平化 key→value */
    @SuppressWarnings("unchecked")
    private Map<String, String> loadSingleConfig(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            Yaml yaml = new Yaml();
            try (Reader reader = Files.newBufferedReader(file)) {
                Object loaded = yaml.loadAs(reader, Object.class);
                if (loaded instanceof Map) {
                    return flattenMap("", (Map<String, Object>) loaded);
                }
                return Collections.emptyMap();
            }
        } else if (name.endsWith(".properties")) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            }
            Map<String, String> flat = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                flat.put(key, props.getProperty(key));
            }
            return flat;
        }
        return Collections.emptyMap();
    }

    /**
     * 递归扁平化嵌套 Map。
     * 如 {@code spring → {datasource → {url → "..."}}} → {"spring.datasource.url": "..."}
     */
    private Map<String, String> flattenMap(String prefix, Map<String, Object> map) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                flat.putAll(flattenMap(key, nested));
            } else if (value != null) {
                flat.put(key, String.valueOf(value));
            }
        }
        return flat;
    }
}
