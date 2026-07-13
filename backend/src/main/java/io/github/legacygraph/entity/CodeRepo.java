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

    /** Git 提供商：github / gitlab / gitea（阶段三-3.2 新增） */
    private String provider;

    /** 默认分支（如 main / master / develop，阶段三-3.2 新增） */
    private String defaultBranch;

    /** 默认 review 团队（阶段三-3.2 新增） */
    private String reviewTeam;

    /**
     * 远程仓库访问令牌（阶段三-3.2 漏点 ⑧ 新增）。
     * <p>建议落地前用 {@code CryptoUtils} 等对称加密落库；当前字段为明文占位，
     * 实际生产部署前应替换为加密方案（如 AES-GCM）。</p>
     */
    private String accessToken;

    private String status;

    private String lastPullStatus;

    private LocalDateTime lastPullTime;

    private LocalDateTime lastScanTime;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
