package io.github.legacygraph.service.pr;

import io.github.legacygraph.dto.change.CreatePrRequest;
import io.github.legacygraph.dto.change.PrResult;
import io.github.legacygraph.dto.change.PrStatusInfo;

import java.util.List;

/**
 * Git 提供商适配器 — 支持 GitHub / GitLab / Gitea（阶段三-3.2）。
 * <p>
 * 本接口仅暴露各提供商真正有差异化实现的能力：
 * 创建 PR、查询 PR 状态、添加 reviewer。
 * 分支创建、文件应用、分支推送这些"git CLI 通用能力"
 * 统一收口到 {@link AbstractGitProviderAdapter} 的 git CLI 实现，
 * 避免不同 Provider 子类出现"签名相同但语义不一致"的问题。
 * </p>
 * <p>实现本接口并注册为 Spring Bean 即可启用对应 Provider；</p>
 * 通过 {@code legacygraph.pr.<provider>.enabled=true} 控制激活。
 */
public interface GitProviderAdapter {

    /**
     * 适配器支持的提供商标识：github / gitlab / gitea。
     */
    String getProviderId();

    /**
     * 创建 Pull Request。
     *
     * @param request 创建 PR 请求
     * @return PR 创建结果
     */
    PrResult createPullRequest(CreatePrRequest request);

    /**
     * 查询 PR 状态。
     *
     * @param prUrl PR URL
     * @return PR 状态信息
     */
    PrStatusInfo getPrStatus(String prUrl);

    /**
     * 添加 reviewer。
     *
     * @param prUrl    PR URL
     * @param reviewers reviewer 列表
     */
    void addReviewer(String prUrl, List<String> reviewers);
}