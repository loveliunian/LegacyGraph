package io.github.legacygraph.security;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Graphify 溯源路径脱敏器。
 * <p>
 * 项目内路径转相对路径，项目外路径返回 [outside-project]。
 * </p>
 */
public class GraphifyProvenanceRedactor {

    private final Path projectRoot;

    public GraphifyProvenanceRedactor(String projectRoot) {
        this.projectRoot = Paths.get(projectRoot).toAbsolutePath().normalize();
    }

    /**
     * 脱敏路径。
     *
     * @param rawPath 原始路径
     * @return 脱敏后的路径
     */
    public String redactPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "[unknown]";
        }

        try {
            Path path = Paths.get(rawPath).toAbsolutePath().normalize();
            if (path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            } else {
                return "[outside-project]";
            }
        } catch (Exception e) {
            return "[invalid-path]";
        }
    }
}
