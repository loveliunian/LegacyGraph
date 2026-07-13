package io.github.legacygraph.service.graph;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.normalize.BusinessDomainNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点 Blocking 服务（graph-merge-optimization-plan.md 改进①）。
 * <p>
 * 解决原 {@code GraphMergeService.normalizeForBlocking} 用 {@code [^a-z0-9]} 剔除所有非字母数字
 * 导致中文全归一为空串、block 退化为"全部塞进同一 block"的问题。
 * </p>
 *
 * <h3>三通道 Blocking</h3>
 * <ul>
 *   <li><b>Channel A（字符级）</b>：nodeType + normalized name 前 3 字符 — 现有逻辑，保留兼容</li>
 *   <li><b>Channel B（n-gram）</b>：中文字符 bigram + 拼音首字母 → block key，阈值 0.3 — 新增</li>
 *   <li><b>Channel C（别名重叠）</b>：aliasNames 交集 → 同 block — 新增（替代 embedding ANN）</li>
 * </ul>
 *
 * <p>性能预期：500 节点从 O(n²)=25 万降到 500 × Top-K 候选。</p>
 */
@Slf4j
@Service
public class NodeBlockingService {

    private final BusinessDomainNormalizer businessDomainNormalizer;

    public NodeBlockingService(BusinessDomainNormalizer businessDomainNormalizer) {
        this.businessDomainNormalizer = businessDomainNormalizer;
    }

    /**
     * 对节点集合执行三通道 Blocking，返回 blockKey → 节点列表 的映射。
     * <p>同一节点可能出现在多个 block 中（多通道召回），由调用方去重候选对。</p>
     *
     * @param nodes    待分组的节点集合（已过滤 deleted）
     * @param nodeType 节点类型（用于 Channel A 的 blockKey 前缀）
     * @return 所有通道的 block 合并后的映射
     */
    public Map<String, List<GraphNode>> buildBlocks(List<GraphNode> nodes, String nodeType) {
        Map<String, List<GraphNode>> blocks = new HashMap<>();

        for (GraphNode node : nodes) {
            String name = node.getNodeName();
            if (name == null || name.isBlank()) {
                continue;
            }

            // ===== Channel A: 字符级 prefix blocking（现有逻辑，保留兼容） =====
            String normalizedA = normalizeForBlockingLegacy(name);
            if (!normalizedA.isEmpty()) {
                String blockKeyA = "A:" + nodeType + ":" + normalizedA.substring(0, Math.min(3, normalizedA.length()));
                blocks.computeIfAbsent(blockKeyA, k -> new ArrayList<>()).add(node);
            }

            // ===== Channel B: 中文 bigram + 拼音首字母 blocking（新增） =====
            // 中文：bigram → 每个bigram作为blockKey（召回同bigram的节点）
            // 英文/混合：拼音首字母作为blockKey
            Set<String> blockKeysB = blockKeyChinese(name, nodeType);
            for (String bk : blockKeysB) {
                blocks.computeIfAbsent(bk, k -> new ArrayList<>()).add(node);
            }

            // ===== Channel C: 别名重叠 blocking（新增，替代 embedding ANN） =====
            // 从 aliasNames 提取每个别名作为 blockKey
            Set<String> blockKeysC = blockKeyByAliases(node, nodeType);
            for (String bk : blockKeysC) {
                blocks.computeIfAbsent(bk, k -> new ArrayList<>()).add(node);
            }
        }

        // 过滤掉只有 1 个节点的 block（无需两两比较）
        blocks.values().removeIf(list -> list.size() < 2);

        log.debug("Blocking produced {} blocks for {} nodes (nodeType={})",
                blocks.size(), nodes.size(), nodeType);
        return blocks;
    }

    /**
     * 中文双通道 Blocking key 生成（改进①核心）。
     * <p>
     * "用户管理" → bigram {"用户","户管","管理"} + 拼音首字母 "yhzx"
     * 每个bigram和拼音首字母都作为独立的blockKey，最大化召回。
     * </p>
     *
     * @param name     节点名称
     * @param nodeType 节点类型（blockKey 前缀）
     * @return blockKey 集合（可能为空）
     */
    public Set<String> blockKeyChinese(String name, String nodeType) {
        if (name == null || name.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        String trimmed = name.trim();

        // B1: 中文字符 bigram
        List<String> bigrams = extractBigrams(trimmed);
        for (String bigram : bigrams) {
            keys.add("B:" + nodeType + ":bi:" + bigram);
        }

        // B2: 拼音首字母（整体）
        String pinyinInitials = businessDomainNormalizer.toPinyinInitials(trimmed);
        if (!pinyinInitials.isBlank()) {
            // 全量拼音首字母作为 blockKey
            keys.add("B:" + nodeType + ":py:" + pinyinInitials);
            // 拼音首字母前 3 字符也作为 blockKey（短名称可能只匹配前缀）
            if (pinyinInitials.length() > 3) {
                keys.add("B:" + nodeType + ":py3:" + pinyinInitials.substring(0, 3));
            }
        }

        // B3: 核心词（后缀剥离后）作为 blockKey
        String core = businessDomainNormalizer.stripSuffix(trimmed);
        if (!core.isBlank() && !core.equals(trimmed)) {
            keys.add("B:" + nodeType + ":core:" + core);
        }

        return keys;
    }

    /**
     * 基于别名重叠的 Blocking key 生成（Channel C）。
     * <p>从 aliasNames JSON 数组中提取每个别名作为 blockKey，
     * 使不同名称但共享别名的节点进入同一 block。</p>
     */
    public Set<String> blockKeyByAliases(GraphNode node, String nodeType) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> aliases = parseAliasNames(node.getAliasNames());
        for (String alias : aliases) {
            String lowerAlias = alias.toLowerCase().trim();
            if (!lowerAlias.isEmpty()) {
                keys.add("C:" + nodeType + ":alias:" + lowerAlias);
            }
        }
        return keys;
    }

    /**
     * 从节点名称提取字符 bigram（2-gram）。
     * <p>"用户管理" → ["用户","户管","管理"]；"user" → ["us","se","er"]</p>
     */
    public List<String> extractBigrams(String text) {
        if (text == null || text.length() < 2) {
            return List.of();
        }
        List<String> bigrams = new ArrayList<>(text.length() - 1);
        for (int i = 0; i < text.length() - 1; i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }

    /**
     * 解析 aliasNames JSON 数组字符串为 List。
     */
    @SuppressWarnings("unchecked")
    public List<String> parseAliasNames(String aliasNamesJson) {
        if (aliasNamesJson == null || aliasNamesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> aliases = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(aliasNamesJson, List.class);
            return aliases != null ? aliases : List.of();
        } catch (Exception e) {
            log.debug("Failed to parse aliasNames {}: {}", aliasNamesJson, e.getMessage());
            return List.of();
        }
    }

    /**
     * 现有归一化逻辑（保留兼容，用于 Channel A）。
     * <p>原 {@code GraphMergeService.normalizeForBlocking} 的逻辑，对中文返回空串。</p>
     */
    public String normalizeForBlockingLegacy(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("controller|service|mapper|dao|impl|entity|dto|vo", "");
    }

    /**
     * 计算 MinHash 签名（基于 bigram 集合）。
     * <p>用于 Channel B 的快速相似度估计。使用固定 hash 函数族模拟 MinHash。</p>
     *
     * @param bigrams bigram 集合
     * @param numHash hash 函数数量（签名长度）
     * @return MinHash 签名数组
     */
    public int[] minHashSignature(Set<String> bigrams, int numHash) {
        int[] signature = new int[numHash];
        Arrays.fill(signature, Integer.MAX_VALUE);
        for (String bigram : bigrams) {
            int baseHash = bigram.hashCode();
            for (int i = 0; i < numHash; i++) {
                // 模拟不同 hash 函数：baseHash * (i+1) + i
                int hash = baseHash * (i + 1) + i * 31;
                if (hash < signature[i]) {
                    signature[i] = hash;
                }
            }
        }
        return signature;
    }

    /**
     * 估算两个 MinHash 签名的 Jaccard 相似度。
     */
    public double estimateSimilarity(int[] sigA, int[] sigB) {
        if (sigA.length != sigB.length || sigA.length == 0) return 0.0;
        int matches = 0;
        for (int i = 0; i < sigA.length; i++) {
            if (sigA[i] == sigB[i]) matches++;
        }
        return (double) matches / sigA.length;
    }
}
