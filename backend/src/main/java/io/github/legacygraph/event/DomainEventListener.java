package io.github.legacygraph.event;

import io.github.legacygraph.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 领域事件监听器
 * 
 * @author LegacyGraph
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    private final NotificationService notificationService;
    
    /**
     * 监听扫描完成事件
     */
    @Async
    @EventListener
    public void handleScanCompleted(ScanCompletedEvent event) {
        log.info("扫描完成事件: projectId={}, versionId={}, nodes={}, edges={}", 
                event.getProjectId(), event.getVersionId(), 
                event.getNodeCount(), event.getEdgeCount());
        notify(event.getProjectId(), "SCAN_COMPLETED", payload(
                "projectId", event.getProjectId(),
                "versionId", event.getVersionId(),
                "nodeCount", event.getNodeCount(),
                "edgeCount", event.getEdgeCount(),
                "summary", "扫描完成：节点 " + event.getNodeCount() + "，边 " + event.getEdgeCount()));
    }
    
    /**
     * 监听图谱更新事件
     */
    @Async
    @EventListener
    public void handleGraphUpdated(GraphUpdatedEvent event) {
        log.debug("图谱更新事件: projectId={}, versionId={}, type={}", 
                event.getProjectId(), event.getVersionId(), event.getUpdateType());
        notify(event.getProjectId(), "GRAPH_UPDATED", payload(
                "projectId", event.getProjectId(),
                "versionId", event.getVersionId(),
                "updateType", event.getUpdateType(),
                "summary", "图谱已更新：" + event.getUpdateType()));
    }
    
    /**
     * 监听项目创建事件
     */
    @Async
    @EventListener
    public void handleProjectCreated(ProjectCreatedEvent event) {
        log.info("项目创建事件: projectId={}, projectName={}", 
                event.getProjectId(), event.getProjectName());
        notify(event.getProjectId(), "PROJECT_CREATED", payload(
                "projectId", event.getProjectId(),
                "projectName", event.getProjectName(),
                "summary", "项目已创建：" + event.getProjectName()));
    }
    
    /**
     * 监听版本创建事件
     */
    @Async
    @EventListener
    public void handleVersionCreated(VersionCreatedEvent event) {
        log.info("版本创建事件: projectId={}, versionId={}, versionName={}", 
                event.getProjectId(), event.getVersionId(), event.getVersionName());
        notify(event.getProjectId(), "VERSION_CREATED", payload(
                "projectId", event.getProjectId(),
                "versionId", event.getVersionId(),
                "versionName", event.getVersionName(),
                "summary", "版本已创建：" + event.getVersionName()));
    }

    @Async
    @EventListener
    public void handleGraphWriteCompleted(GraphWriteCompletedEvent event) {
        log.info("图谱写入完成事件: projectId={}, versionId={}, intents={}",
                event.getProjectId(), event.getVersionId(), event.getIntentCount());
        notify(event.getProjectId(), event.eventType(), payload(
                "projectId", event.getProjectId(),
                "versionId", event.getVersionId(),
                "intentCount", event.getIntentCount(),
                "summary", "图谱写入完成：" + event.getIntentCount() + " 条意图"));
    }

    private void notify(String projectId, String eventType, Map<String, Object> payload) {
        try {
            notificationService.sendNotification(projectId, eventType, payload);
        } catch (Exception e) {
            log.warn("发送领域事件通知失败: projectId={}, eventType={}, err={}",
                    projectId, eventType, e.getMessage());
        }
    }

    private Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return payload;
    }
}
