package io.github.legacygraph.event;

import java.util.Map;

/**
 * 图谱写入完成事件 — 当一批 WriteIntent 执行完毕后发布
 */
public class GraphWriteCompletedEvent extends DomainEvent {

    private final String projectId;
    private final String versionId;
    private final int intentCount;

    public GraphWriteCompletedEvent(String projectId, String versionId, int intentCount) {
        this.projectId = projectId;
        this.versionId = versionId;
        this.intentCount = intentCount;
    }

    @Override
    public String eventType() {
        return "GRAPH_WRITE_COMPLETED";
    }

    @Override
    public String aggregateId() {
        return versionId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
                "projectId", projectId,
                "versionId", versionId,
                "intentCount", intentCount
        );
    }

    public String getProjectId() { return projectId; }
    public String getVersionId() { return versionId; }
    public int getIntentCount() { return intentCount; }
}
