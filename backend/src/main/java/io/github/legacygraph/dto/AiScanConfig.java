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

    /** G8 业务编排：业务流程 → API 映射（mapBusinessProcessesToApis） */
    private boolean processToApiMapping = true;

    /** G8 业务编排：业务对象 → 数据表映射（mapBusinessObjectsToTables） */
    private boolean objectToTableMapping = true;

    /** G8 业务编排：业务域 → 代码映射（mapBusinessDomainsToCode） */
    private boolean domainToCodeMapping = true;

    /** G8 业务编排：跨语言 Feature 合并（mergeCrossLanguageFeatures） */
    private boolean crossLanguageFeatureMerge = true;

    /** G8 业务编排：Feature → 代码映射（mapFeaturesToCode） */
    private boolean featureToCodeMapping = true;

    /** G8 业务编排：BusinessProcess → BusinessDomain 归类（评估 §4 真空区 1，H06 默认开） */
    private boolean processToDomain = true;

    /** G8 业务编排：BusinessObject → Mapper（IMPLEMENTED_BY 边，评估 §4 真空区 2 拆分） */
    private boolean objectToMapperMapping = true;

    /** G8 业务编排：BusinessRule → 代码层 Rule 节点（评估 §4 真空区 3，H06 默认开） */
    private boolean ruleToRuleMapping = true;

    /** H27: LLM 语义归类开关（独立于 processToDomain 规则映射，默认 ON） */
    private boolean llmProcessDomainClassification = true;

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
     * 这样"是否启用 AI 编排"可由后端 {@code legacygraph.ai.*} 配置统一控制，
     * 单次扫描请求仍可在 scanScope 中覆盖。解析失败时回退到 defaults。</p>
     */
    public static AiScanConfig fromScanScope(String scanScopeJson, ObjectMapper objectMapper,
                                             AiScanConfig defaults) {
        AiScanConfig base = defaults != null ? defaults : new AiScanConfig();
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(base.isEnableAi());
        config.setAutoGenerateTestCase(base.isAutoGenerateTestCase());
        config.setMinConfidence(base.getMinConfidence());
        config.setProcessToApiMapping(base.isProcessToApiMapping());
        config.setObjectToTableMapping(base.isObjectToTableMapping());
        config.setDomainToCodeMapping(base.isDomainToCodeMapping());
        config.setCrossLanguageFeatureMerge(base.isCrossLanguageFeatureMerge());
        config.setFeatureToCodeMapping(base.isFeatureToCodeMapping());
        config.setProcessToDomain(base.isProcessToDomain());
        config.setObjectToMapperMapping(base.isObjectToMapperMapping());
        config.setRuleToRuleMapping(base.isRuleToRuleMapping());
        config.setLlmProcessDomainClassification(base.isLlmProcessDomainClassification());

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
            // G8 业务编排细粒度开关：scanScope 显式指定 > defaults 基线（默认 true）
            if (node.hasNonNull("processToApiMapping")) {
                config.setProcessToApiMapping(node.get("processToApiMapping").asBoolean(config.isProcessToApiMapping()));
            }
            if (node.hasNonNull("objectToTableMapping")) {
                config.setObjectToTableMapping(node.get("objectToTableMapping").asBoolean(config.isObjectToTableMapping()));
            }
            if (node.hasNonNull("domainToCodeMapping")) {
                config.setDomainToCodeMapping(node.get("domainToCodeMapping").asBoolean(config.isDomainToCodeMapping()));
            }
            if (node.hasNonNull("crossLanguageFeatureMerge")) {
                config.setCrossLanguageFeatureMerge(node.get("crossLanguageFeatureMerge").asBoolean(config.isCrossLanguageFeatureMerge()));
            }
            if (node.hasNonNull("featureToCodeMapping")) {
                config.setFeatureToCodeMapping(node.get("featureToCodeMapping").asBoolean(config.isFeatureToCodeMapping()));
            }
            // G8 业务编排 — 评估 §4 矩阵真空区 1/2/3 闭环
            if (node.hasNonNull("processToDomain")) {
                config.setProcessToDomain(node.get("processToDomain").asBoolean(config.isProcessToDomain()));
            }
            if (node.hasNonNull("objectToMapperMapping")) {
                config.setObjectToMapperMapping(node.get("objectToMapperMapping").asBoolean(config.isObjectToMapperMapping()));
            }
            if (node.hasNonNull("ruleToRuleMapping")) {
                config.setRuleToRuleMapping(node.get("ruleToRuleMapping").asBoolean(config.isRuleToRuleMapping()));
            }
            // H27: LLM 语义归类开关（独立于规则映射 processToDomain）
            if (node.hasNonNull("llmProcessDomainClassification")) {
                config.setLlmProcessDomainClassification(node.get("llmProcessDomainClassification")
                        .asBoolean(config.isLlmProcessDomainClassification()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI scan config from scanScope, using defaults: {}", e.getMessage());
        }
        return config;
    }
}
