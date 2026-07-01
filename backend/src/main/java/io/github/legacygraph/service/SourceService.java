package io.github.legacygraph.service;

import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 数据源服务 — 从 SourceController 提取的业务逻辑。
 * 负责代码仓库 git 操作、数据库连接测试、文档解析等。
 *
 * <p>⚠️ 持续重构中：当前仅提取核心 git/JDBC 逻辑，完整拆分见 B-H3 TODO。</p>
 */
@Slf4j
@Service
public class SourceService {

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;

    public SourceService(CodeRepoRepository codeRepoRepository,
                         DbConnectionRepository dbConnectionRepository,
                         DocumentRepository documentRepository,
                         DocChunkRepository docChunkRepository) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
        this.docChunkRepository = docChunkRepository;
    }

    // ==================== Git 操作 ====================

    /**
     * 测试 Git 仓库连接。
     * @return {success: boolean, message: string}
     */
    public Map<String, Object> testGitConnection(String repoUrl) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", repoUrl);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("message", "连接超时（30秒）");
                return result;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                result.put("success", true);
                result.put("message", "连接成功");
            } else {
                result.put("message", "连接测试失败，exit code: " + exitCode);
            }
        } catch (Exception e) {
            result.put("message", "连接测试失败: " + e.getMessage());
            log.error("Git connection test failed", e);
        }
        return result;
    }

    /**
     * 拉取代码仓库到本地。
     */
    public Map<String, Object> pullRepository(CodeRepo repo) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);

        String localPath = repo.getLocalPath();
        if (localPath == null) {
            result.put("message", "本地路径未配置");
            return result;
        }

        Path repoPath = Paths.get(localPath);
        try {
            if (Files.exists(repoPath.resolve(".git"))) {
                // git pull
                ProcessBuilder pb = new ProcessBuilder("git", "pull");
                pb.directory(repoPath.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    repo.setLastPullStatus("TIMEOUT");
                    result.put("message", "拉取超时");
                } else if (process.exitValue() == 0) {
                    repo.setLastPullStatus("SUCCESS");
                    result.put("success", true);
                    result.put("message", "拉取成功");
                } else {
                    repo.setLastPullStatus("FAILED");
                    result.put("message", "拉取失败，exit code: " + process.exitValue());
                }
            } else {
                // git clone
                Files.createDirectories(repoPath.getParent());
                ProcessBuilder pb = new ProcessBuilder("git", "clone", repo.getGitUrl(), localPath);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    repo.setLastPullStatus("TIMEOUT");
                    result.put("message", "克隆超时");
                } else if (process.exitValue() == 0) {
                    repo.setLastPullStatus("SUCCESS");
                    result.put("success", true);
                    result.put("message", "克隆成功");
                } else {
                    repo.setLastPullStatus("FAILED");
                    result.put("message", "克隆失败，exit code: " + process.exitValue());
                }
            }
            repo.setLastPullTime(LocalDateTime.now());
            codeRepoRepository.updateById(repo);
        } catch (Exception e) {
            repo.setStatus("PULL_FAILED");
            repo.setLastPullStatus("FAILED");
            repo.setLastPullTime(LocalDateTime.now());
            codeRepoRepository.updateById(repo);
            result.put("message", "操作失败: " + e.getMessage());
            log.error("Git pull/clone failed for repo {}", repo.getId(), e);
        }
        return result;
    }

    // ==================== DB 连接测试 ====================

    /**
     * 测试数据库连接。
     */
    public Map<String, Object> testDbConnection(DbConnection conn) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        String url = buildJdbcUrl(conn);
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword())) {
            result.put("success", true);
            result.put("message", "连接成功 — " + c.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            result.put("message", "连接失败: " + e.getMessage());
            log.warn("DB connection test failed: {}", url, e);
        }
        return result;
    }

    private String buildJdbcUrl(DbConnection conn) {
        String dbType = conn.getDbType() != null ? conn.getDbType().toLowerCase() : "postgresql";
        String host = conn.getHost() != null ? conn.getHost() : "localhost";
        int port = conn.getPort() != null ? conn.getPort() : ("mysql".equals(dbType) || "mariadb".equals(dbType) ? 3306 : 5432);
        String dbName = conn.getDatabaseName() != null ? conn.getDatabaseName() : "";
        return switch (dbType) {
            case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?sslmode=disable";
            case "mysql", "mariadb" -> "jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            case "oracle" -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + dbName;
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    // ==================== 文档操作 ====================

    /**
     * 解析文档并分块存档。
     */
    public Map<String, Object> parseDocument(String projectId, String docId) {
        Map<String, Object> result = new HashMap<>();
        Document doc = documentRepository.selectById(docId);
        if (doc == null) {
            result.put("success", false);
            result.put("message", "文档不存在");
            return result;
        }
        // 标记为解析中
        doc.setParseStatus("PARSING");
        documentRepository.updateById(doc);

        try {
            // TODO: 实际解析逻辑（PDF/DOCX/MD → 文本切片）
            doc.setParseStatus("PARSED");
            doc.setFactCount(0);
            documentRepository.updateById(doc);
            result.put("success", true);
            result.put("factCount", 0);
        } catch (Exception e) {
            doc.setParseStatus("FAILED");
            documentRepository.updateById(doc);
            result.put("success", false);
            result.put("message", "解析失败: " + e.getMessage());
            log.error("Document parse failed: docId={}", docId, e);
        }
        return result;
    }

    /**
     * 存储文件到本地仓库目录。
     */
    public String resolveRepoPath(String projectId, String repoName) {
        String home = System.getProperty("user.home");
        return home + "/.legacygraph/repos/" + projectId + "/" + repoName;
    }
}
