package io.github.legacygraph.extractors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import io.github.legacygraph.model.FrontendPageFact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 路由配置抽取器
 * 抽取路由路径、组件、标题、权限等信息
 */
@Slf4j
public class VueRouteExtractor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class ExtractedRoute {
        private String path;
        private String name;
        private String component;
        private String redirect;
        private Meta meta;
        private List<ExtractedRoute> children = new ArrayList<>();
    }

    @Data
    public static class Meta {
        private String title;
        private String icon;
        private Object permission; // can be string or array
        private Boolean hidden;
    }

    /**
     * 从路由文件中抽取路由信息
     * 支持: TypeScript (.ts) JavaScript (.js)
     */
    public List<FrontendPageFact> extractFromFile(Path file) throws IOException {
        List<FrontendPageFact> result = new ArrayList<>();
        String content = Files.readString(file);

        // 尝试从不同格式提取路由配置
        // 模式1: 直接数组
        // const routes: RouteConfig[] = [ ... ]
        // 模式2: 导出默认
        // export default routes
        List<ExtractedRoute> extractedRoutes = parseRoutes(content);

        // 转换为PageFact
        for (ExtractedRoute route : extractedRoutes) {
            convertToPageFacts(route, null, result);
        }

        log.info("Extracted {} pages from {}", result.size(), file);
        return result;
    }

    /**
     * 解析路由数组
     */
    private List<ExtractedRoute> parseRoutes(String content) {
        List<ExtractedRoute> routes = new ArrayList<>();

        // 尝试匹配数组字面量
        // 查找 [... 格式的路由定义
        Pattern arrayPattern = Pattern.compile("\\[(.*)\\]", Pattern.DOTALL);
        Matcher matcher = arrayPattern.matcher(content);

        // 简单启发式提取：找到第一个大数组，假设就是路由
        if (matcher.find()) {
            String arrayContent = matcher.group(1);
            // 转换为近似JSON解析
            String json = approximateJson(arrayContent);
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        ExtractedRoute route = parseRouteNode(item);
                        if (route != null) {
                            routes.add(route);
                        }
                    }
                }
            } catch (Exception e) {
                // 解析失败，尝试手动正则提取
                log.debug("Failed to parse route as JSON: {}", e.getMessage());
                routes.addAll(extractRoutesByRegex(content));
            }
        } else {
            routes.addAll(extractRoutesByRegex(content));
        }

        return routes;
    }

    /**
     * 正则提取路由（当JSON解析失败时）
     */
    private List<ExtractedRoute> extractRoutesByRegex(String content) {
        List<ExtractedRoute> routes = new ArrayList<>();

        // 匹配 { path: '/xxx', ... }
        Pattern routePattern = Pattern.compile("\\{\\s*path:\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = routePattern.matcher(content);

        while (matcher.find()) {
            String path = matcher.group(1);
            ExtractedRoute route = new ExtractedRoute();
            route.setPath(path);

            // 尝试提取name
            int start = matcher.end();
            int end = content.indexOf("}", start);
            if (end > start && end - start < 500) {
                String block = content.substring(start, end);

                // name
                Pattern namePat = Pattern.compile("name:\\s*['\"]([^'\"]+)['\"]");
                Matcher nameMatch = namePat.matcher(block);
                if (nameMatch.find()) {
                    route.setName(nameMatch.group(1));
                }

                // component
                Pattern compPat = Pattern.compile("component:\\s*(\\w+)");
                Matcher compMatch = compPat.matcher(block);
                if (compMatch.find()) {
                    route.setComponent(compMatch.group(1));
                }

                // meta.title
                Pattern titlePat = Pattern.compile("title:\\s*['\"]([^'\"]+)['\"]");
                Matcher titleMatch = titlePat.matcher(block);
                if (titleMatch.find()) {
                    if (route.getMeta() == null) route.setMeta(new Meta());
                    route.getMeta().setTitle(titleMatch.group(1));
                }

                // meta.permission
                Pattern permPat = Pattern.compile("permission:\\s*['\"]([^'\"]+)['\"]");
                Matcher permMatch = permPat.matcher(block);
                if (permMatch.find()) {
                    if (route.getMeta() == null) route.setMeta(new Meta());
                    route.getMeta().setPermission(permMatch.group(1));
                }
            }

            routes.add(route);
        }

        return routes;
    }

    /**
     * 将JS对象转换为近似JSON
     */
    private String approximateJson(String content) {
        // 移除注释
        content = content.replaceAll("//.*", "");
        // 将单引号换为双引号
        content = content.replace('\'', '"');
        // 给未加引号的键添加引号
        // { key: value } → { "key": value }
        content = content.replaceAll("(^|\\{|,)\\s*(\\w+)\\s*:", "$1\"$2\":");
        return "[" + content + "]";
    }

    /**
     * 解析单个路由节点
     */
    private ExtractedRoute parseRouteNode(JsonNode node) {
        ExtractedRoute route = new ExtractedRoute();
        if (node.has("path")) {
            route.setPath(node.get("path").asText());
        }
        if (node.has("name")) {
            route.setName(node.get("name").asText());
        }
        if (node.has("component")) {
            if (node.get("component").isTextual()) {
                route.setComponent(node.get("component").asText());
            }
        }
        if (node.has("redirect")) {
            route.setRedirect(node.get("redirect").asText());
        }
        if (node.has("meta")) {
            JsonNode metaNode = node.get("meta");
            Meta meta = new Meta();
            if (metaNode.has("title")) {
                meta.setTitle(metaNode.get("title").asText());
            }
            if (metaNode.has("icon")) {
                meta.setIcon(metaNode.get("icon").asText());
            }
            if (metaNode.has("permission")) {
                if (metaNode.get("permission").isTextual()) {
                    meta.setPermission(metaNode.get("permission").asText());
                }
            }
            route.setMeta(meta);
        }
        if (node.has("children") && node.get("children").isArray()) {
            for (JsonNode child : node.get("children")) {
                ExtractedRoute childRoute = parseRouteNode(child);
                if (childRoute != null) {
                    route.getChildren().add(childRoute);
                }
            }
        }
        return route;
    }

    /**
     * 将ExtractedRoute转换为FrontendPageFact
     */
    private void convertToPageFacts(ExtractedRoute route, String parentPath, List<FrontendPageFact> result) {
        String fullPath = (parentPath != null ? parentPath : "") + route.getPath();
        if (!fullPath.startsWith("/")) {
            fullPath = "/" + fullPath;
        }

        FrontendPageFact page = new FrontendPageFact();
        page.setRoutePath(fullPath);
        page.setRouteName(route.getName());
        page.setComponentPath(route.getComponent());

        if (route.getMeta() != null) {
            Meta meta = route.getMeta();
            page.setTitle(meta.getTitle());
            page.setPermission(meta.getPermission() != null ? meta.getPermission().toString() : null);
            page.setIcon(meta.getIcon());
        }

        if (route.getChildren() != null && !route.getChildren().isEmpty()) {
            List<FrontendPageFact> children = new ArrayList<>();
            for (ExtractedRoute child : route.getChildren()) {
                convertToPageFacts(child, fullPath, children);
            }
            page.setChildren(children);
        }

        result.add(page);
        result.addAll(page.getChildren());
    }
}
