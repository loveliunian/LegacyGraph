package io.github.legacygraph.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 提示词模板加载器
 * 从 classpath:/prompts/ 加载模板文件，支持变量替换
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 渲染模板，替换变量 {variable}
     */
    public String render(String templateName, Map<String, String> variables) {
        String template = getTemplate(templateName);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(key, value);
        }
        return result;
    }

    /**
     * 获取模板，优先从缓存读取
     */
    private String getTemplate(String templateName) {
        return templateCache.computeIfAbsent(templateName, this::loadTemplate);
    }

    /**
     * 从classpath加载模板
     */
    private String loadTemplate(String templateName) {
        String path = "/prompts/" + templateName + ".txt";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.error("Prompt template not found: {}", path);
                throw new IllegalArgumentException("Prompt template not found: " + templateName);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Loaded prompt template: {} ({} chars)", templateName, content.length());
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", path, e);
            throw new RuntimeException("Failed to load prompt template: " + templateName, e);
        }
    }

    /**
     * 清除模板缓存，重新加载
     */
    public void clearCache() {
        templateCache.clear();
        log.info("Prompt template cache cleared");
    }
}
