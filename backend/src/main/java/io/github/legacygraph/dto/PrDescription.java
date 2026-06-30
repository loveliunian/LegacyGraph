package io.github.legacygraph.dto;

import lombok.Data;

/**
 * PR 描述 / 提交信息生成结果 — 对应 pr-description 模板输出。
 */
@Data
public class PrDescription {

    private String commitMessage;
    private String prTitle;
    private String prBody;
    private String changeType;
}
