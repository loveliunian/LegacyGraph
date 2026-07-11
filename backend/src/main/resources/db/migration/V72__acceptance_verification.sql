-- ============================================
-- V72: 验收条件闭环验证（G-14）
--
-- 为 lg_acceptance_criterion 增加验证状态、验证类型、
-- 验证人、验证时间、验证备注、证据链接等字段，
-- 支撑基于 AcceptanceCriterion 的闭环验证能力。
-- ============================================

-- 1. 验证状态：PENDING / VERIFIED / WAIVED
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'PENDING';

-- 2. 验证类型：AUTOMATIC / MANUAL / NONE
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS verification_type VARCHAR(16);

-- 3. 验证人
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS verified_by VARCHAR(64);

-- 4. 验证时间
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;

-- 5. 验证备注
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS verification_note TEXT;

-- 6. 证据链接（如测试报告、截图地址）
ALTER TABLE lg_acceptance_criterion ADD COLUMN IF NOT EXISTS evidence_url VARCHAR(512);

COMMENT ON COLUMN lg_acceptance_criterion.status IS '验证状态：PENDING/VERIFIED/WAIVED';
COMMENT ON COLUMN lg_acceptance_criterion.verification_type IS '验证类型：AUTOMATIC/MANUAL/NONE';
COMMENT ON COLUMN lg_acceptance_criterion.verified_by IS '验证人';
COMMENT ON COLUMN lg_acceptance_criterion.verified_at IS '验证时间';
COMMENT ON COLUMN lg_acceptance_criterion.verification_note IS '验证备注';
COMMENT ON COLUMN lg_acceptance_criterion.evidence_url IS '证据链接';
