-- V34: 系统通知表
CREATE TABLE IF NOT EXISTS lg_notifications (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL,
    event_type  VARCHAR(50) NOT NULL,
    payload     JSONB,
    read        BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_project ON lg_notifications(project_id);
CREATE INDEX idx_notifications_created ON lg_notifications(created_at);
CREATE INDEX idx_notifications_read ON lg_notifications(read);
