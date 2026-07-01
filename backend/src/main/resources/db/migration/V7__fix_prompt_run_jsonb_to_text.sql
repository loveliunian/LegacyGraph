-- V7: 将 lg_prompt_run 的 JSONB 列改为 TEXT
-- 原因: maskedInput 存的是脱敏后的明文 prompt 字符串；rawOutput/parsedOutput
--   在 responseType==String.class 时存的是纯文本 LLM 响应，均非 JSON 结构
-- 与 H2 测试 schema (schema-h2.sql lines 549-551) 保持一致

ALTER TABLE lg_prompt_run ALTER COLUMN masked_input TYPE TEXT;
ALTER TABLE lg_prompt_run ALTER COLUMN raw_output TYPE TEXT;
ALTER TABLE lg_prompt_run ALTER COLUMN parsed_output TYPE TEXT;
