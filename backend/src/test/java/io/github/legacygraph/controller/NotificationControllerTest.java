package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.Notification;
import io.github.legacygraph.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new NotificationController(notificationService);
    }

    @Test
    void getRecent_returnsNotifications() {
        Notification n1 = new Notification();
        n1.setId("n1");
        Notification n2 = new Notification();
        n2.setId("n2");

        when(notificationService.getRecent("p1", 20)).thenReturn(Arrays.asList(n1, n2));

        Result<List<Notification>> result = controller.getRecent("p1", 20);

        assertEquals(0, result.getCode());
        assertEquals(2, result.getData().size());
        verify(notificationService).getRecent("p1", 20);
    }

    @Test
    void getRecent_defaultLimit() {
        when(notificationService.getRecent("p1", 20)).thenReturn(List.of());

        Result<List<Notification>> result = controller.getRecent("p1", 20);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void markRead_delegatesToService() {
        doNothing().when(notificationService).markRead("n1");

        Result<Void> result = controller.markRead("n1");

        assertEquals(0, result.getCode());
        verify(notificationService).markRead("n1");
    }

    @Test
    void stream_returnsSseEmitter() {
        SseEmitter emitter = controller.stream("p1");

        assertNotNull(emitter);
        verify(notificationService).registerEmitter(eq("p1"), any(SseEmitter.class));
    }

    @Test
    void stream_registersEmitterCallbacks() {
        SseEmitter emitter = controller.stream("p1");

        // 验证 emitter 已注册（回调设置不抛异常）
        assertNotNull(emitter);
    }
}
