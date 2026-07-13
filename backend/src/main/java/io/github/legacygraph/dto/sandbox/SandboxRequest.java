package io.github.legacygraph.dto.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 沙箱执行请求（阶段三-3.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxRequest {

    /** 关联的变更任务 ID */
    private String changeTaskId;

    /** 待验证的文件列表（文件路径 + 变更内容） */
    private List<FileChange> files;

    /** 需执行的门禁类型：STATIC / UNIT / API / DB / E2E / MIGRATION */
    private List<String> gateTypes;

    /** 命令类门禁工作目录 */
    private String workingDir;

    /** 环境：test / staging */
    private String environment;

    /**
     * 单个文件变更。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileChange {
        private String filePath;
        private String changeType; // CREATE / MODIFY / DELETE
        private String content;
    }
}
