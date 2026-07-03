package io.github.legacygraph.event;

import org.springframework.context.ApplicationEvent;

/**
 * 图谱更新事件
 * 
 * @author LegacyGraph
 */
public class GraphUpdatedEvent extends ApplicationEvent {
    
    private final String projectId;
    private final String versionId;
    private final UpdateType updateType;
    
    public enum UpdateType {
        NODE_CREATED,
        NODE_UPDATED,
        NODE_DELETED,
        EDGE_CREATED,
        EDGE_UPDATED,
        EDGE_DELETED,
        BATCH_UPDATE
    }
    
    public GraphUpdatedEvent(Object source, String projectId, String versionId, 
                             UpdateType updateType) {
        super(source);
        this.projectId = projectId;
        this.versionId = versionId;
        this.updateType = updateType;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    public String getVersionId() {
        return versionId;
    }
    
    public UpdateType getUpdateType() {
        return updateType;
    }
}
