package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_code_repo")
public class CodeRepo {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String repoName;

    private String repoType;

    private String gitUrl;

    private String branchName;

    private String authType;

    private String username;

    private String includePattern;

    private String excludePattern;

    /**
     * 全栈项目-后端子路径 (仅 repoType=FULLSTACK 时使用)
     */
    private String backendSubPath;

    /**
     * 全栈项目-前端子路径 (仅 repoType=FULLSTACK 时使用)
     */
    private String frontendSubPath;

    private String localPath;

    private String status;

    private String lastPullStatus;

    private LocalDateTime lastPullTime;

    private LocalDateTime lastScanTime;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
