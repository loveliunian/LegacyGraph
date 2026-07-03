package io.github.legacygraph.event;

import org.springframework.context.ApplicationEvent;

/**
 * 项目创建事件
 * 
 * @author LegacyGraph
 */
public class ProjectCreatedEvent extends ApplicationEvent {
    
    private final String projectId;
    private final String projectName;
    
    public ProjectCreatedEvent(Object source, String projectId, String projectName) {
        super(source);
        this.projectId = projectId;
        this.projectName = projectName;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    public String getProjectName() {
        return projectName;
    }
}
