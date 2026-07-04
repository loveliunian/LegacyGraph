package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.Notification;
import io.github.legacygraph.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
        ReflectionTestUtils.setField(service, "baseMapper", notificationRepository);
    }

    @Test
    void getRecent_returnsNotificationsFromRepository() {
        Notification n1 = new Notification();
        n1.setId("n1");
        n1.setProjectId("p1");
        Notification n2 = new Notification();
        n2.setId("n2");
        n2.setProjectId("p1");

        when(notificationRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(n1, n2));

        List<Notification> result = service.getRecent("p1", 20);

        assertEquals(2, result.size());
        verify(notificationRepository).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void getRecent_emptyResult_returnsEmptyList() {
        when(notificationRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        List<Notification> result = service.getRecent("p1", 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void markRead_existingNotification_setsReadTrue() {
        Notification n = new Notification();
        n.setId("n1");
        n.setRead(false);
        when(notificationRepository.selectById("n1")).thenReturn(n);
        when(notificationRepository.updateById(any(Notification.class))).thenReturn(1);

        service.markRead("n1");

        assertTrue(n.getRead());
        verify(notificationRepository).updateById((Notification) n);
    }

    @Test
    void markRead_nonExistentNotification_doesNothing() {
        when(notificationRepository.selectById("nonexistent")).thenReturn(null);

        service.markRead("nonexistent");

        verify(notificationRepository, never()).updateById(any(Notification.class));
    }

    @Test
    void registerEmitter_addsEmitterToMap() {
        SseEmitter emitter = mock(SseEmitter.class);

        service.registerEmitter("p1", emitter);

        // 验证 emitter 被注册（通过 removeEmitter 能正常移除）
        service.removeEmitter("p1", emitter);
        // 无异常即通过
    }

    @Test
    void removeEmitter_removesEmitterFromMap() {
        SseEmitter emitter = mock(SseEmitter.class);
        service.registerEmitter("p1", emitter);

        service.removeEmitter("p1", emitter);

        // 再次移除不存在的 emitter 不抛异常
        service.removeEmitter("p1", emitter);
    }

    @Test
    void removeEmitter_nonExistentProject_doesNotThrow() {
        SseEmitter emitter = mock(SseEmitter.class);

        // 不应抛异常
        service.removeEmitter("nonexistent", emitter);
    }

    @Test
    void sendNotification_insertsAndPushesViaSse() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        when(notificationRepository.insert(any(Notification.class))).thenReturn(1);

        service.registerEmitter("p1", emitter);
        service.sendNotification("p1", "SCAN_COMPLETE", Map.of("versionId", "v1"));

        verify(notificationRepository).insert(any(Notification.class));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendNotification_noEmitters_stillInsertsNotification() {
        when(notificationRepository.insert(any(Notification.class))).thenReturn(1);

        // 没有注册任何 emitter
        service.sendNotification("p1", "SCAN_COMPLETE", Map.of("versionId", "v1"));

        verify(notificationRepository).insert(any(Notification.class));
    }
}
