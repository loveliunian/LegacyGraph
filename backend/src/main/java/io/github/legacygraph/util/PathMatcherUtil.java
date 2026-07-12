package io.github.legacygraph.util;

import org.springframework.util.AntPathMatcher;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Ant 风格 pattern 的路径匹配工具，用于扫描时的 include/exclude 裁剪。
 * <p>
 * Pattern 语法沿用 Spring {@link AntPathMatcher}，支持 Ant 通配符（双星号匹配任意目录层级等）。
 * 多个 pattern 用逗号分隔，任一命中即视为匹配。
 * null 或空 pattern 编译出的 matcher 匹配所有文件（返回 true）。
 */
public final class PathMatcherUtil {

    private static final AntPathMatcher ANT = new AntPathMatcher();

    private PathMatcherUtil() {
    }

    /** 路径匹配器：判断 path 相对于 root 是否命中给定 pattern 集合。 */
    public interface PathMatcher {
        boolean matches(Path path, Path root);
    }

    /**
     * 把逗号分隔的 Ant pattern 字符串编译为 PathMatcher。
     * null 或空白 pattern 返回匹配所有的 matcher。
     * 多个 pattern 用逗号分隔，任一命中即返回 true。
     */
    public static PathMatcher compile(String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return (path, root) -> true; // 空 pattern 匹配所有
        }
        List<String> patternList = Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (patternList.isEmpty()) {
            return (path, root) -> true;
        }
        return (path, root) -> {
            String relative = root.relativize(path).toString().replace('\\', '/');
            return patternList.stream().anyMatch(p -> ANT.match(p, relative));
        };
    }

    /**
     * 检查 path 相对于 root 是否匹配 matcher。
     */
    public static boolean matches(PathMatcher matcher, Path path, Path root) {
        return matcher.matches(path, root);
    }
}
