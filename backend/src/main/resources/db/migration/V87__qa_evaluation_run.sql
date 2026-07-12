-- S4-T6: QA 评估指标落库 — 评测结果从纯 JSON 文件改为可查询的数据库表
CREATE TABLE IF NOT EXISTS lg_qa_evaluation_run (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    project_id      VARCHAR(64)  NOT NULL,
    version_id      VARCHAR(64),
    evaluated_at    TIMESTAMP    NOT NULL,
    -- 核心指标
    entity_recall           DOUBLE PRECISION DEFAULT 0,
    evidence_precision      DOUBLE PRECISION DEFAULT 0,
    required_keyword_coverage DOUBLE PRECISION DEFAULT 0,
    abstention_accuracy     DOUBLE PRECISION DEFAULT 0,
    -- Ragas 指标
    ragas_context_precision   DOUBLE PRECISION DEFAULT 0,
    ragas_context_recall      DOUBLE PRECISION DEFAULT 0,
    ragas_faithfulness        DOUBLE PRECISION DEFAULT 0,
    ragas_answer_relevancy    DOUBLE PRECISION DEFAULT 0,
    -- 汇总
    total_cases    INT  DEFAULT 0,
    passed_cases   INT  DEFAULT 0,
    passed         BOOLEAN DEFAULT FALSE,
    failure_reasons TEXT,
    report_file_path VARCHAR(512),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qa_eval_run_project ON lg_qa_evaluation_run(project_id);
CREATE INDEX IF NOT EXISTS idx_qa_eval_run_version ON lg_qa_evaluation_run(version_id);
CREATE INDEX IF NOT EXISTS idx_qa_eval_run_time ON lg_qa_evaluation_run(evaluated_at DESC);
