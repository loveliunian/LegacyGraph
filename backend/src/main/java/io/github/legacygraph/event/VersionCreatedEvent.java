package io.github.legacygraph.event;

import org.springframework.context.ApplicationEvent;

/**
 * 版本创建事件
 * 
 * @author LegacyGraph
 */
public class VersionCreatedEvent extends ApplicationEvent {
    
    private final String projectId;
    private final String versionId;
    private final String versionName;
    
    public VersionCreatedEvent(Object source, String projectId, String versionId, 
                               String versionName) {
        super(source);
        this.projectId = projectId;
        this.versionId = versionId;
        this.versionName = versionName;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    public String getVersionId() {
        return versionId;
    }
    
    public String getVersionName() {
        return versionName;
    }
}
