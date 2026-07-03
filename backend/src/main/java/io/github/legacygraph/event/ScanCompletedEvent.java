package io.github.legacygraph.event;

import org.springframework.context.ApplicationEvent;

/**
 * 扫描完成事件
 * 
 * @author LegacyGraph
 */
public class ScanCompletedEvent extends ApplicationEvent {
    
    private final String projectId;
    private final String versionId;
    private final int nodeCount;
    private final int edgeCount;
    
    public ScanCompletedEvent(Object source, String projectId, String versionId, 
                              int nodeCount, int edgeCount) {
        super(source);
        this.projectId = projectId;
        this.versionId = versionId;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    public String getVersionId() {
        return versionId;
    }
    
    public int getNodeCount() {
        return nodeCount;
    }
    
    public int getEdgeCount() {
        return edgeCount;
    }
}
