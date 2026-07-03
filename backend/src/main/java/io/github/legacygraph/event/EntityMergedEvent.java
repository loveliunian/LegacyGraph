package io.github.legacygraph.event;

import java.util.Map;

/**
 * 实体合并事件 — 当两个重复节点被合并为一个时发布
 */
public class EntityMergedEvent extends DomainEvent {

    private final String projectId;
    private final String versionId;
    private final String fromNodeId;
    private final String toNodeId;
    private final String mergedNodeId;

    public EntityMergedEvent(String projectId, String versionId,
                             String fromNodeId, String toNodeId, String mergedNodeId) {
        this.projectId = projectId;
        this.versionId = versionId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.mergedNodeId = mergedNodeId;
    }

    @Override
    public String eventType() {
        return "ENTITY_MERGED";
    }

    @Override
    public String aggregateId() {
        return mergedNodeId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
                "projectId", projectId,
                "versionId", versionId,
                "fromNodeId", fromNodeId,
                "toNodeId", toNodeId,
                "mergedNodeId", mergedNodeId
        );
    }

    public String getProjectId() { return projectId; }
    public String getVersionId() { return versionId; }
    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public String getMergedNodeId() { return mergedNodeId; }
}
