ALTER TABLE lg_graph_release ADD COLUMN IF NOT EXISTS metrics TEXT;
COMMENT ON COLUMN lg_graph_release.metrics IS '质量指标 JSON（GraphifyQualityResult 等）';
