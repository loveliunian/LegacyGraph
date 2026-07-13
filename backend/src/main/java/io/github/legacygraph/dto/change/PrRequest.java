package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * PR 创建请求（阶段三-3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrRequest {

    private String changeTaskId;
    private String sourceBranch;
    private String targetBranch;
    private String commitMessage;
    private String prTitle;
    private String prBody;
    private List<DraftFile> files;
    private Map<String, String> metadata;
}
