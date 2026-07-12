-- H25: 流程一致性校验结果表
-- 存储 PM4Py conformance checking 的 fitness/precision/generalization 指标
CREATE TABLE IF NOT EXISTS lg_process_fitness (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    process_id BIGINT NOT NULL,
    fitness NUMERIC(5,4),
    precision NUMERIC(5,4),
    generalization NUMERIC(5,4),
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_process_fitness_pv ON lg_process_fitness(project_id, version_id);
CREATE INDEX IF NOT EXISTS idx_process_fitness_process ON lg_process_fitness(process_id);
