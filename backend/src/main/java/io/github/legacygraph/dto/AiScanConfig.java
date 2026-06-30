package io.github.legacygraph.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 扫描后 AI 编排配置 — 从 ScanVersion.scanScope (JSON) 中解析。
 *
 * <p>对应前端创建扫描页的开关：启用 AI 归纳(enableAi)、自动生成测试用例(autoGenerateTestCase)、
 * 最低置信度(minConfidence)。</p>
 */
@Slf4j
@Data
public class AiScanConfig {

    /** 是否启用扫描后 AI 编排（文档抽取、功能映射、审核准备） */
    private boolean enableAi = false;

    /** 是否自动生成测试用例 */
    private boolean autoGenerateTestCase = false;

    /** 最低置信度阈值：低于此值的节点进入人工审核准备 */
    private double minConfidence = 0.6;

    /**
     * 从 scanScope JSON 文本解析；解析失败返回默认（关闭 AI）配置。
     */
    public static AiScanConfig fromScanScope(String scanScopeJson, ObjectMapper objectMapper) {
        AiScanConfig config = new AiScanConfig();
        if (scanScopeJson == null || scanScopeJson.isBlank()) {
            return config;
        }
        try {
            JsonNode node = objectMapper.readTree(scanScopeJson);
            if (node.hasNonNull("enableAi")) {
                config.setEnableAi(node.get("enableAi").asBoolean(false));
            }
            if (node.hasNonNull("autoGenerateTestCase")) {
                config.setAutoGenerateTestCase(node.get("autoGenerateTestCase").asBoolean(false));
            }
            if (node.hasNonNull("minConfidence")) {
                config.setMinConfidence(node.get("minConfidence").asDouble(0.6));
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI scan config from scanScope, using defaults: {}", e.getMessage());
        }
        return config;
    }
}
