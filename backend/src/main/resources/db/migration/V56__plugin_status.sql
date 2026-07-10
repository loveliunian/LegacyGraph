-- 插件启用/禁用状态持久化表
CREATE TABLE IF NOT EXISTS plugin_status (
    plugin_id  VARCHAR(128) PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
