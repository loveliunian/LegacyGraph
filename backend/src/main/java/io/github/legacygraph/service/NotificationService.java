package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.entity.Notification;
import io.github.legacygraph.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class NotificationService extends ServiceImpl<NotificationRepository, Notification> {

    // projectId -> List<SseEmitter>
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public List<Notification> getRecent(String projectId, int limit) {
        return baseMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getProjectId, projectId)
                        .orderByDesc(Notification::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    public void markRead(String id) {
        Notification n = baseMapper.selectById(id);
        if (n != null) {
            n.setRead(true);
            baseMapper.updateById(n);
        }
    }

    public void registerEmitter(String projectId, SseEmitter emitter) {
        emitters.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void removeEmitter(String projectId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    /**
     * 发送通知到前端（SSE 推送）
     */
    public void sendNotification(String projectId, String eventType, Map<String, Object> payload) {
        Notification n = new Notification();
        n.setProjectId(projectId);
        n.setEventType(eventType);
        n.setPayload(payload);
        n.setRead(false);
        baseMapper.insert(n);

        CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("notification")
                            .data(n));
                } catch (IOException e) {
                    log.warn("Failed to send SSE to client: {}", e.getMessage());
                    list.remove(emitter);
                }
            }
        }
    }
}
