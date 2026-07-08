package io.github.legacygraph.terminology;

import java.util.List;
import java.util.Set;

/**
 * 术语服务：提供跨语言（中文 ↔ 英文子词）名称分词与相似度计算。
 *
 * <p>把原先散落在 {@code BusinessGraphBuilder} 中的中文术语词典、分词与 token 重叠相似度
 * 逻辑收敛为独立服务，便于复用、独立测试与配置化调优。</p>
 */
public interface TerminologyService {

    /**
     * 计算两个名称的跨语言相似度。
     *
     * @return 0~1 之间的相似度分数；任一参数为空返回 0
     */
    double calculateSimilarity(String name1, String name2);

    /**
     * 将名称拆分为英文关键词 token 集合（camelCase 拆分 + 中文术语翻译 + 词干归一）。
     */
    Set<String> tokenize(String name);

    /**
     * 基于预计算 token 集合的相似度，供笛卡尔积场景复用分词结果、避免重复分词。
     *
     * @param tokens1 name1 的 token 集合
     * @param tokens2 name2 的 token 集合
     */
    double similarityOfTokens(Set<String> tokens1, Set<String> tokens2, String name1, String name2);

    /**
     * 获取某中文术语的英文子词映射（无映射时返回空列表）。
     */
    List<String> getTargetTerms(String sourceTerm);
}
