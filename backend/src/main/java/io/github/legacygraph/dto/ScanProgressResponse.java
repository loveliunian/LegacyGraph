package io.github.legacygraph.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 扫描进度响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanProgressResponse {

    private String versionId;
    private String status;
    private Integer progress;
    private List<TaskProgress> tasks;

    @Data
    @NoArgsConstructor
@AllArgsConstructor
    public static class TaskProgress {
        private String taskType;
        private String status;
        private Integer factCount;
    }
}
