-- ============================================
-- V10: 证据去重 — content_hash 唯一索引 + 清理历史重复数据
-- ============================================

-- 1. 清理重复的 NodeEvidence 关联（指向重复 evidence 的关联，重定向到保留的那条）
WITH duplicate_groups AS (
    SELECT content_hash,
           MIN(created_at) AS keep_created_at
    FROM lg_evidence
    WHERE content_hash IS NOT NULL
      AND deleted = 0
    GROUP BY content_hash
    HAVING COUNT(*) > 1
),
kept AS (
    SELECT e.id AS keep_id, e.content_hash
    FROM lg_evidence e
    JOIN duplicate_groups d ON e.content_hash = d.content_hash AND e.created_at = d.keep_created_at
),
dup AS (
    SELECT e.id AS dup_id, e.content_hash
    FROM lg_evidence e
    JOIN duplicate_groups d ON e.content_hash = d.content_hash
    WHERE e.created_at > d.keep_created_at
)
UPDATE lg_node_evidence ne
SET evidence_id = k.keep_id
FROM dup d
JOIN kept k ON d.content_hash = k.content_hash
WHERE ne.evidence_id = d.dup_id;

-- 2. 清理重复的 EdgeEvidence 关联
WITH duplicate_groups AS (
    SELECT content_hash,
           MIN(created_at) AS keep_created_at
    FROM lg_evidence
    WHERE content_hash IS NOT NULL
      AND deleted = 0
    GROUP BY content_hash
    HAVING COUNT(*) > 1
),
kept AS (
    SELECT e.id AS keep_id, e.content_hash
    FROM lg_evidence e
    JOIN duplicate_groups d ON e.content_hash = d.content_hash AND e.created_at = d.keep_created_at
),
dup AS (
    SELECT e.id AS dup_id, e.content_hash
    FROM lg_evidence e
    JOIN duplicate_groups d ON e.content_hash = d.content_hash
    WHERE e.created_at > d.keep_created_at
)
UPDATE lg_edge_evidence ee
SET evidence_id = k.keep_id
FROM dup d
JOIN kept k ON d.content_hash = k.content_hash
WHERE ee.evidence_id = d.dup_id;

-- 3. 清理重复的 NodeEvidence（同一 (node_id, evidence_id) 去重后的多余记录）
DELETE FROM lg_node_evidence
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY node_id, evidence_id ORDER BY created_at) AS rn
        FROM lg_node_evidence
        WHERE deleted = 0
    ) sub
    WHERE rn > 1
);

-- 4. 清理重复的 EdgeEvidence（同一 (edge_id, evidence_id) 去重后的多余记录）
DELETE FROM lg_edge_evidence
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY edge_id, evidence_id ORDER BY created_at) AS rn
        FROM lg_edge_evidence
        WHERE deleted = 0
    ) sub
    WHERE rn > 1
);

-- 5. 删除重复的 evidence 记录（保留最早的）
DELETE FROM lg_evidence
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               content_hash,
               ROW_NUMBER() OVER (PARTITION BY content_hash ORDER BY created_at) AS rn
        FROM lg_evidence
        WHERE content_hash IS NOT NULL AND deleted = 0
    ) sub
    WHERE rn > 1
);

-- 6. 创建 content_hash 部分唯一索引（仅对非 NULL 的非删除记录生效）
CREATE UNIQUE INDEX IF NOT EXISTS idx_lg_evidence_content_hash_unique
    ON lg_evidence(content_hash)
    WHERE content_hash IS NOT NULL AND deleted = 0;

COMMENT ON INDEX idx_lg_evidence_content_hash_unique IS '证据内容哈希去重索引：相同内容的证据只保留一条';
