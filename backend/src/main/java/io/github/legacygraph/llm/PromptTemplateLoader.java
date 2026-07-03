package io.github.legacygraph.llm;

import io.github.legacygraph.entity.PromptTemplate;
import io.github.legacygraph.service.system.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 提示词模板加载器。
 *
 * <p>优先从 DB（lg_prompt_template 表）加载模板，若 DB 中无匹配模板则回退到
 * classpath:/prompts/ 下的 .txt 文件。</p>
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    private final PromptTemplateService promptTemplateService;
    private final Map<String, String> fileCache = new ConcurrentHashMap<>();

    public PromptTemplateLoader(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 渲染模板，替换变量 {variable}。
     * 优先从 DB 加载，DB 无匹配则回退到 classpath 文件。
     */
    public String render(String templateName, Map<String, String> variables) {
        // 1. 尝试从 DB 加载
        PromptTemplate dbTemplate = promptTemplateService.getActiveByCode(templateName);
        if (dbTemplate != null) {
            return renderFromDb(dbTemplate, variables);
        }

        // 2. 回退到 classpath 文件
        log.debug("DB template not found for '{}', falling back to classpath file", templateName);
        String template = getFileTemplate(templateName);
        return replaceVariables(template, variables);
    }

    /**
     * 从 DB 模板渲染完整 Prompt。
     * 合并 systemPrompt + domainPrompt + taskPrompt 三部分。
     */
    public String renderFromDb(PromptTemplate template, Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();

        // 系统角色提示词
        if (template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank()) {
            sb.append(template.getSystemPrompt()).append("\n\n");
        }

        // 领域知识提示词
        if (template.getDomainPrompt() != null && !template.getDomainPrompt().isBlank()) {
            sb.append(template.getDomainPrompt()).append("\n\n");
        }

        // 任务指令提示词
        if (template.getTaskPrompt() != null && !template.getTaskPrompt().isBlank()) {
            sb.append(template.getTaskPrompt());
        }

        String merged = sb.toString().trim();

        // P1: 若 task_prompt 中不含变量占位符（如 {docContent}），自动附加变量数据段。
        // 否则 replaceVariables 扫描不到占位符，LLM 收不到实际数据，无法产出结论。
        boolean hasPlaceholders = false;
        if (variables != null && !variables.isEmpty()) {
            for (String varName : variables.keySet()) {
                if (merged.contains("{" + varName + "}")) {
                    hasPlaceholders = true;
                    break;
                }
            }
        }
        if (!hasPlaceholders && variables != null && !variables.isEmpty()) {
            StringBuilder dataSection = new StringBuilder("\n\n## 输入数据\n");
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    dataSection.append("\n### ").append(entry.getKey()).append("\n```\n")
                            .append(value).append("\n```\n");
                }
            }
            merged += dataSection.toString();
        }

        // 注入输出格式约束
        if (template.getOutputSchema() != null && !template.getOutputSchema().isBlank()) {
            merged += "\n\n## 输出格式\n请以严格 JSON 格式输出，遵循以下 Schema：\n```json\n"
                    + template.getOutputSchema() + "\n```\n\n只输出 JSON，不要其他解释内容。";
        }

        return replaceVariables(merged, variables);
    }

    /**
     * 替换模板中的 {variable} 占位符。
     */
    private String replaceVariables(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(key, value);
        }
        return result;
    }

    /**
     * 从 classpath 加载 .txt 文件模板（回退路径）。
     */
    private String getFileTemplate(String templateName) {
        return fileCache.computeIfAbsent(templateName, this::loadFileTemplate);
    }

    private String loadFileTemplate(String templateName) {
        String path = "/prompts/" + templateName + ".txt";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.error("Prompt template not found in DB or file: {}", templateName);
                throw new IllegalArgumentException("Prompt template not found: " + templateName);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Loaded prompt template from file: {} ({} chars)", templateName, content.length());
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt template from file: {}", path, e);
            throw new RuntimeException("Failed to load prompt template: " + templateName, e);
        }
    }

    /**
     * 清除模板缓存，重新加载。
     */
    public void clearCache() {
        fileCache.clear();
        log.info("Prompt template file cache cleared");
    }
}
