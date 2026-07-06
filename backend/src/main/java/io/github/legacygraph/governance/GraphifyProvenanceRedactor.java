package io.github.legacygraph.governance;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Graphify 来源信息脱敏器。
 * <p>
 * 将项目内的绝对路径转换为相对路径，项目外的路径标记为 [outside-project]。
 * </p>
 */
@Component
public class GraphifyProvenanceRedactor {

    private final Path projectRoot;

    public GraphifyProvenanceRedactor() {
        // 默认项目根目录为当前工作目录
        this.projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public GraphifyProvenanceRedactor(String projectRoot) {
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().normalize();
    }

    /**
     * 脱敏路径。
     * <p>
     * 如果路径在项目根目录内，转换为相对路径；
     * 如果路径在项目根目录外，返回 [outside-project]。
     * </p>
     *
     * @param rawPath 原始路径
     * @return 脱敏后的路径
     */
    public String redactPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "[unknown]";
        }

        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            
            if (path.startsWith(projectRoot)) {
                // 项目内路径：转换为相对路径
                return projectRoot.relativize(path).toString();
            } else {
                // 项目外路径：脱敏
                return "[outside-project]";
            }
        } catch (Exception e) {
            // 路径解析失败，保守处理
            return "[invalid-path]";
        }
    }

    /**
     * 检查路径是否在项目内。
     *
     * @param rawPath 原始路径
     * @return true 如果路径在项目根目录内
     */
    public boolean isInsideProject(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }

        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            return path.startsWith(projectRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取项目根目录。
     *
     * @return 项目根目录的绝对路径
     */
    public String getProjectRoot() {
        return projectRoot.toString();
    }
}
