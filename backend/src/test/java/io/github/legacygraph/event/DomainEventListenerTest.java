package io.github.legacygraph.event;

import io.github.legacygraph.service.NotificationService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DomainEventListenerTest {

    @Test
    void graphWriteCompletedSendsNotification() {
        NotificationService notificationService = mock(NotificationService.class);
        DomainEventListener listener = new DomainEventListener(notificationService);

        listener.handleGraphWriteCompleted(new GraphWriteCompletedEvent("project-1", "v1", 3));

        verify(notificationService).sendNotification(
                eq("project-1"),
                eq("GRAPH_WRITE_COMPLETED"),
                argThat(payload -> "v1".equals(payload.get("versionId")) && Integer.valueOf(3).equals(payload.get("intentCount"))));
    }
}
