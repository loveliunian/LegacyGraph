package io.github.legacygraph.integration.graphify;

/**
 * Graphify JSON schema 版本枚举。
 * <p>
 * Graphify 输出格式存在两种变体：
 * <ul>
 *   <li>V0_9_NETWORKX_LINKS: NetworkX node-link 格式，边字段名为 {@code links}</li>
 *   <li>V0_9_NETWORKX_EDGES: NetworkX node-link 格式，边字段名为 {@code edges}</li>
 * </ul>
 */
public enum GraphifySchemaVersion {
    V0_9_NETWORKX_LINKS,
    V0_9_NETWORKX_EDGES,
    UNKNOWN;

    /**
     * 根据 JSON 内容检测 schema 版本。
     *
     * @param json 已解析的 Graphify JSON
     * @return 检测到的版本
     */
    public static GraphifySchemaVersion detect(GraphifyGraphJson json) {
        if (json == null) {
            return UNKNOWN;
        }

        if (json.links() != null && !json.links().isEmpty()) {
            return V0_9_NETWORKX_LINKS;
        }

        if (json.edges() != null && !json.edges().isEmpty()) {
            return V0_9_NETWORKX_EDGES;
        }

        return UNKNOWN;
    }
}
