package io.github.legacygraph.service.pr;

import io.github.legacygraph.dto.change.DraftFile;

import java.util.List;

/**
 * Git 通用操作抽象基类 — 阶段三-3.2 接口收口。
 * <p>
 * 把 {@link GitProviderAdapter} 中"git CLI 通用能力"（分支创建、补丁应用、分支推送）
 * 集中到这里使用 ProcessBuilder 调用本地 git 命令，避免不同 Provider 子类出现
 * 接口签名相同但实现语义不一致的问题。
 * </p>
 * <p>子类只需关注 GitHub/GitLab 等 API 调用的差异化部分。</p>
 */
public abstract class AbstractGitProviderAdapter implements GitProviderAdapter {

    /** git 命令执行超时时间（秒） */
    protected static final int GIT_COMMAND_TIMEOUT_SEC = 60;

    /**
     * 创建远程分支：在本地仓库 checkout 到 baseBranch 后创建新分支。
     *
     * @param localPath   本地仓库路径
     * @param branchName  新分支名
     * @param baseBranch  基础分支
     */
    public void createBranch(String localPath, String branchName, String baseBranch) throws Exception {
        runGit(localPath, "git", "checkout", baseBranch);
        runGit(localPath, "git", "checkout", "-b", branchName);
    }

    /**
     * 应用补丁文件到本地仓库：CREATE/MODIFY 写入、DELETE 删除，最后 git add。
     *
     * @param localPath 本地仓库路径
     * @param files     文件变更列表
     */
    public void applyPatch(String localPath, List<DraftFile> files) throws Exception {
        if (files == null || files.isEmpty()) {
            return;
        }
        java.nio.file.Path repoPath = java.nio.file.Paths.get(localPath);
        for (DraftFile file : files) {
            if (file.getFilePath() == null || file.getFilePath().isBlank()) {
                continue;
            }
            java.nio.file.Path fullPath = repoPath.resolve(file.getFilePath());
            String changeType = file.getChangeType() != null
                    ? file.getChangeType().toUpperCase() : "MODIFY";
            switch (changeType) {
                case "DELETE" -> java.nio.file.Files.deleteIfExists(fullPath);
                case "CREATE", "MODIFY" -> {
                    if (fullPath.getParent() != null) {
                        java.nio.file.Files.createDirectories(fullPath.getParent());
                    }
                    String content = file.getNewContent() != null ? file.getNewContent() : "";
                    java.nio.file.Files.writeString(fullPath, content, java.nio.charset.StandardCharsets.UTF_8);
                }
                default -> { /* 未知类型忽略 */ }
            }
        }
        runGit(localPath, "git", "add", "-A");
    }

    /**
     * 推送分支到远程仓库（带 token 注入）。
     *
     * @param localPath  本地仓库路径
     * @param branchName 分支名
     * @param remoteUrl  远程 URL（可包含 token）
     * @param authType   认证类型：NONE/TOKEN/PASSWORD
     * @param username   远程认证用户名（可空）
     * @param accessToken 访问令牌（可空）
     */
    public void pushBranch(String localPath, String branchName, String remoteUrl,
                           String authType, String username, String accessToken) throws Exception {
        if ("TOKEN".equalsIgnoreCase(authType)
                && username != null && !username.isBlank()
                && accessToken != null && !accessToken.isBlank()) {
            String tokenRemote = injectToken(remoteUrl, username, accessToken);
            runGit(localPath, "git", "push", "-u", tokenRemote, branchName);
        } else {
            runGit(localPath, "git", "push", "-u", "origin", branchName);
        }
    }

    /**
     * 把 token 注入到 https URL。
     * https://github.com/owner/repo.git → https://username:token@github.com/owner/repo.git
     */
    protected String injectToken(String remoteUrl, String username, String accessToken) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return remoteUrl;
        }
        if (!remoteUrl.startsWith("https://")) {
            return remoteUrl;
        }
        String withoutScheme = remoteUrl.substring("https://".length());
        return "https://" + username + ":" + accessToken + "@" + withoutScheme;
    }

    /** 执行 git 命令并返回 stdout。 */
    protected String runGit(String workingDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new java.io.File(workingDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(GIT_COMMAND_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("git 命令超时: " + String.join(" ", command));
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}