package io.github.legacygraph.service.qa;

/**
 * NLI（自然语言推理）客户端接口 — 用于证据验证的语义匹配评分。
 * <p>
 * 给定 premise（证据文本）和 hypothesis（答案声明），
 * 返回 entailment 分数（0.0~1.0），越高表示证据越能支撑声明。
 * </p>
 * <p>
 * 典型实现：{@link HttpNliClient}（指向内网 HHEM-2.1-Open 服务）。
 * </p>
 */
public interface NliClient {

    /**
     * 评估 premise 对 hypothesis 的蕴含分数。
     *
     * @param premise    证据文本（premise）
     * @param hypothesis 答案声明（hypothesis）
     * @return 蕴含分数 0.0~1.0；服务不可用时返回 -1 表示降级
     */
    double score(String premise, String hypothesis);

    /**
     * 健康检查 — NLI 服务是否可用。
     *
     * @return true 表示服务可用
     */
    boolean isAvailable();
}
