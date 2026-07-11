package io.github.legacygraph.service.scan;

import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
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

    /** L-01: 密码加解密服务（可选） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.security.SecretCipher secretCipher;

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;
    private final ScanVersionRepository scanVersionRepository;

    public SourceService(CodeRepoRepository codeRepoRepository,
                         DbConnectionRepository dbConnectionRepository,
                         DocumentRepository documentRepository,
                         DocChunkRepository docChunkRepository,
                         ScanVersionRepository scanVersionRepository) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
        this.docChunkRepository = docChunkRepository;
        this.scanVersionRepository = scanVersionRepository;
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
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), resolveDbPassword(conn))) {
            result.put("success", true);
            result.put("message", "连接成功 — " + c.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            result.put("message", "连接失败: " + e.getMessage());
            log.warn("DB connection test failed: {}", url, e);
        }
        return result;
    }

    /**
     * L-01: 解密数据库连接密码。
     */
    private String resolveDbPassword(DbConnection conn) {
        if (conn == null) return "";
        if (secretCipher != null && conn.getPasswordCipher() != null && !conn.getPasswordCipher().isEmpty()) {
            try {
                return secretCipher.decrypt(conn.getPasswordCipher());
            } catch (Exception e) {
                log.warn("Failed to decrypt password for connection {}: {}", conn.getId(), e.getMessage());
            }
        }
        return conn.getPassword() != null ? conn.getPassword() : "";
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
     * 使用 DocumentExtractor 抽取 PDF/DOCX/MD/TXT 文本并按标题切分成 DocChunk 存档。
     */
    public Map<String, Object> parseDocument(String projectId, String docId) {
        Map<String, Object> result = new HashMap<>();
        Document doc = documentRepository.selectById(docId);
        if (doc == null) {
            result.put("success", false);
            result.put("message", "文档不存在");
            return result;
        }

        String filePath = doc.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            result.put("success", false);
            result.put("message", "文档文件路径为空，无法解析");
            return result;
        }

        String versionId = doc.getVersionId();
        if (versionId != null && !versionId.isBlank()) {
            if (scanVersionRepository.selectById(versionId) == null) {
                result.put("success", false);
                result.put("message", "版本不存在: " + versionId);
                return result;
            }
        }

        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            result.put("success", false);
            result.put("message", "文档文件不存在: " + filePath);
            return result;
        }

        // 标记为解析中
        doc.setParseStatus("PARSING");
        documentRepository.updateById(doc);

        try {
            // 使用 DocumentExtractor 抽取文本
            io.github.legacygraph.extractors.DocumentExtractor extractor =
                    new io.github.legacygraph.extractors.DocumentExtractor();
            String text = extractor.extractText(file);

            // 按标题切分成文档片段（每片最多 2000 tokens）
            int maxTokensPerChunk = 2000;
            List<io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk> chunks =
                    extractor.chunkDocument(text, doc.getDocName(), maxTokensPerChunk);

            // 保存文档片段到 lg_doc_chunk 表
            int savedChunks = 0;
            for (io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk chunk : chunks) {
                io.github.legacygraph.entity.DocChunk docChunk = new io.github.legacygraph.entity.DocChunk();
                docChunk.setProjectId(projectId);
                docChunk.setVersionId(doc.getVersionId());
                docChunk.setDocName(doc.getDocName());
                docChunk.setDocPath(filePath);
                docChunk.setChunkIndex(chunk.getIndex());
                docChunk.setTitlePath(chunk.getTitlePath());
                docChunk.setContent(chunk.getContent());
                docChunk.setTokenCount(chunk.getTokenCount());
                docChunk.setCreatedAt(LocalDateTime.now());
                docChunkRepository.insert(docChunk);
                savedChunks++;
            }

            // 更新文档状态
            doc.setParseStatus("PARSED");
            doc.setFactCount(savedChunks);
            doc.setParsedAt(LocalDateTime.now());
            documentRepository.updateById(doc);

            result.put("success", true);
            result.put("factCount", savedChunks);
            result.put("chunkCount", savedChunks);
            log.info("Document parsed successfully: docId={}, chunks={}, filePath={}", docId, savedChunks, filePath);
        } catch (Exception e) {
            doc.setParseStatus("FAILED");
            doc.setErrorMessage(e.getMessage());
            documentRepository.updateById(doc);
            result.put("success", false);
            result.put("message", "解析失败: " + e.getMessage());
            log.error("Document parse failed: docId={}, filePath={}", docId, filePath, e);
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
