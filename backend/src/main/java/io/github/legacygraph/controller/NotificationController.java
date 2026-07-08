package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.Notification;
import io.github.legacygraph.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/lg/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 获取最近通知
     */
    @GetMapping("/recent")
    public Result<List<Notification>> getRecent(
            @RequestParam String projectId,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(notificationService.getRecent(projectId, limit));
    }

    /**
     * 标记通知已读
     */
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable String id) {
        notificationService.markRead(id);
        return Result.success();
    }

    /**
     * SSE 推送通知
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam String projectId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        notificationService.registerEmitter(projectId, emitter);
        
        emitter.onCompletion(() -> notificationService.removeEmitter(projectId, emitter));
        emitter.onTimeout(() -> notificationService.removeEmitter(projectId, emitter));
        emitter.onError(e -> notificationService.removeEmitter(projectId, emitter));
        
        return emitter;
    }
}
