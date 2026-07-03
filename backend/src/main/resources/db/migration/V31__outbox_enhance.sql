-- 增加并发锁字段
ALTER TABLE lg_graph_write_intent ADD COLUMN IF NOT EXISTS running_lock VARCHAR(64);
ALTER TABLE lg_graph_write_intent ADD COLUMN IF NOT EXISTS running_lock_at TIMESTAMP;

-- 增加死信标记
ALTER TABLE lg_graph_write_intent ADD COLUMN IF NOT EXISTS dead_letter BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lg_graph_write_intent ADD COLUMN IF NOT EXISTS dead_letter_reason TEXT;

-- 增加优先级
ALTER TABLE lg_graph_write_intent ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;

-- 优化查询索引
CREATE INDEX IF NOT EXISTS idx_gwi_pending_priority
    ON lg_graph_write_intent (status, priority DESC, created_at ASC)
    WHERE status IN ('PENDING', 'RETRYING');

-- 死信查询索引
CREATE INDEX IF NOT EXISTS idx_gwi_dead_letter
    ON lg_graph_write_intent (dead_letter, created_at)
    WHERE dead_letter = TRUE;
