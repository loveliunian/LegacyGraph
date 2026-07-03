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
        return fromScanScope(scanScopeJson, objectMapper, new AiScanConfig());
    }

    /**
     * 从 scanScope JSON 文本解析，以 {@code defaults} 作为基线。
     *
     * <p>开关优先级：scanScope 中显式指定的字段 &gt; 后端配置项默认值（defaults）。
     * 这样"是否启用 AI 编排"可由后端 {@code legacy-graph.ai.*} 配置统一控制，
     * 单次扫描请求仍可在 scanScope 中覆盖。解析失败时回退到 defaults。</p>
     */
    public static AiScanConfig fromScanScope(String scanScopeJson, ObjectMapper objectMapper,
                                             AiScanConfig defaults) {
        AiScanConfig base = defaults != null ? defaults : new AiScanConfig();
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(base.isEnableAi());
        config.setAutoGenerateTestCase(base.isAutoGenerateTestCase());
        config.setMinConfidence(base.getMinConfidence());

        if (scanScopeJson == null || scanScopeJson.isBlank()) {
            return config;
        }
        try {
            JsonNode node = objectMapper.readTree(scanScopeJson);
            if (node.hasNonNull("enableAi")) {
                config.setEnableAi(node.get("enableAi").asBoolean(config.isEnableAi()));
            } else if (node.hasNonNull("aiEnabled")) {
                // 兼容旧字段 aiEnabled（运行库历史 scanScope 可能使用此字段名）
                config.setEnableAi(node.get("aiEnabled").asBoolean(config.isEnableAi()));
            }
            if (node.hasNonNull("autoGenerateTestCase")) {
                config.setAutoGenerateTestCase(node.get("autoGenerateTestCase").asBoolean(config.isAutoGenerateTestCase()));
            }
            if (node.hasNonNull("minConfidence")) {
                config.setMinConfidence(node.get("minConfidence").asDouble(config.getMinConfidence()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI scan config from scanScope, using defaults: {}", e.getMessage());
        }
        return config;
    }
}
