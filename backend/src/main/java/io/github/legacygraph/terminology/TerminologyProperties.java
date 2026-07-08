package io.github.legacygraph.terminology;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 相似度配置 —— 从 application.yml 读取 {@code terminology.similarity.*} 配置。
 *
 * <p>中文业务术语 → 英文子词的映射表已迁入数据库表 {@code lg_terminology_mapping}
 * （见 {@code ConfigurableTerminologyService}），以后新增术语直接入库即可，无需改配置重新部署。
 * 此处仅保留相似度阈值，便于按环境调优。</p>
 */
@Data
@ConfigurationProperties(prefix = "terminology")
public class TerminologyProperties {

    /** 相似度计算配置。 */
    private SimilarityConfig similarity = new SimilarityConfig();

    @Data
    public static class SimilarityConfig {
        /** 单 token 集合命中时，共享 token 的最小长度（低于此长度不认，避免短 token 误匹配）。 */
        private int minTokenLength = 4;
        /** 小集合完全包含阈值：token 数小于此值时必须完全包含才认。 */
        private int smallSetThreshold = 3;
        /** 无法分词时，包含关系（a.contains(b)）的相似度分数。 */
        private double containsSimilarity = 0.7;
        /** 无法分词时，忽略大小写完全相等的相似度分数。 */
        private double exactMatchSimilarity = 0.9;
        /** 包含关系兜底时要求的最小名称长度，避免单字符假匹配。 */
        private int minMatchLength = 3;
    }
}
