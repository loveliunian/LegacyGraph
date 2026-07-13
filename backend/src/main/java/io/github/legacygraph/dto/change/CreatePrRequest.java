package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建 PR 请求（GitProviderAdapter 使用，阶段三-3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePrRequest {

    /** 仓库远程 URL */
    private String repoUrl;

    /** 源分支 */
    private String sourceBranch;

    /** 目标分支 */
    private String targetBranch;

    /** PR 标题 */
    private String prTitle;

    /** PR 描述 */
    private String prBody;

    /** reviewer 列表 */
    private List<String> reviewers;
}
