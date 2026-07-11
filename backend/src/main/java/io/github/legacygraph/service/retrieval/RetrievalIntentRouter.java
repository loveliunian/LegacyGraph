package io.github.legacygraph.service.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * G-07: RetrievalIntentRouter — 检索意图路由与加权。
 * <p>将用户查询分类为 6 种意图，每种意图对应不同的检索策略和权重矩阵。</p>
 *
 * <p>意图类型：</p>
 * <ul>
 *   <li>CODE_LOOKUP: 代码查找（类/方法/字段）→ 权重: 代码图谱 0.7, 文档 0.2, DB 0.1</li>
 *   <li>DATA_LINEAGE: 数据血缘（表→表）→ 权重: DB 0.6, 代码 0.3, 文档 0.1</li>
 *   <li>API_CHAIN: API 调用链 → 权重: 代码 0.6, API 0.3, 文档 0.1</li>
 *   <li>BUSINESS_FLOW: 业务流程 → 权重: 文档 0.5, 代码 0.3, DB 0.2</li>
 *   <li>IMPACT_ANALYSIS: 影响分析 → 权重: 代码 0.5, DB 0.3, 文档 0.2</li>
 *   <li>GENERAL: 通用问答 → 权重: 均匀 0.33/0.33/0.34</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalIntentRouter {

    /**
     * 意图类型枚举。
     */
    public enum Intent {
        CODE_LOOKUP,
        DATA_LINEAGE,
        API_CHAIN,
        BUSINESS_FLOW,
        IMPACT_ANALYSIS,
        GENERAL
    }

    /** 意图 → 权重矩阵（代码, 文档, DB） */
    private static final Map<Intent, double[]> WEIGHT_MATRIX = new EnumMap<>(Intent.class);

    static {
        WEIGHT_MATRIX.put(Intent.CODE_LOOKUP, new double[]{0.7, 0.2, 0.1});
        WEIGHT_MATRIX.put(Intent.DATA_LINEAGE, new double[]{0.3, 0.1, 0.6});
        WEIGHT_MATRIX.put(Intent.API_CHAIN, new double[]{0.6, 0.1, 0.3});
        WEIGHT_MATRIX.put(Intent.BUSINESS_FLOW, new double[]{0.3, 0.5, 0.2});
        WEIGHT_MATRIX.put(Intent.IMPACT_ANALYSIS, new double[]{0.5, 0.2, 0.3});
        WEIGHT_MATRIX.put(Intent.GENERAL, new double[]{0.33, 0.34, 0.33});
    }

    /**
     * 分类查询意图。
     * <p>基于关键词匹配进行简单分类。后续可替换为 LLM 分类。</p>
     *
     * @param query 用户查询文本
     * @return 意图类型
     */
    public Intent classifyIntent(String query) {
        if (query == null || query.isBlank()) {
            return Intent.GENERAL;
        }
        String lower = query.toLowerCase();

        // 关键词匹配
        if (lower.contains("调用链") || lower.contains("api chain") || lower.contains("call chain")) {
            return Intent.API_CHAIN;
        }
        if (lower.contains("血缘") || lower.contains("lineage") || lower.contains("数据流")
                || lower.contains("data flow") || lower.contains("影响") || lower.contains("impact")) {
            if (lower.contains("影响") || lower.contains("impact")) {
                return Intent.IMPACT_ANALYSIS;
            }
            return Intent.DATA_LINEAGE;
        }
        if (lower.contains("业务流程") || lower.contains("business") || lower.contains("功能")
                || lower.contains("feature") || lower.contains("流程")) {
            return Intent.BUSINESS_FLOW;
        }
        if (lower.contains("类") || lower.contains("class") || lower.contains("方法")
                || lower.contains("method") || lower.contains("字段") || lower.contains("field")
                || lower.contains("接口") || lower.contains("interface")) {
            return Intent.CODE_LOOKUP;
        }
        return Intent.GENERAL;
    }

    /**
     * 获取意图对应的权重矩阵。
     *
     * @param intent 意图类型
     * @return 权重数组 [代码权重, 文档权重, DB权重]
     */
    public double[] getWeights(Intent intent) {
        return WEIGHT_MATRIX.getOrDefault(intent, WEIGHT_MATRIX.get(Intent.GENERAL));
    }

    /**
     * 路由检索 — 分类意图并返回权重。
     *
     * @param query 用户查询
     * @return 包含意图和权重的结果 Map
     */
    public Map<String, Object> route(String query) {
        Intent intent = classifyIntent(query);
        double[] weights = getWeights(intent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent.name());
        result.put("weights", Map.of(
                "code", weights[0],
                "document", weights[1],
                "database", weights[2]
        ));
        log.debug("Retrieval intent routed: query='{}', intent={}", query, intent);
        return result;
    }
}
