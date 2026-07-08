package io.github.legacygraph.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

/** 统一领域事件基类 */
public abstract class DomainEvent {
    private final String eventId = IdUtil.fastUUID();
    private final Instant timestamp = Instant.now();
    
    public abstract String eventType();
    public abstract String aggregateId();
    public abstract Map<String, Object> payload();
    
    public String getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }
}
