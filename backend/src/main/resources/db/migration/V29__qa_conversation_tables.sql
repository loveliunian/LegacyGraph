-- QA 对话历史表
CREATE TABLE IF NOT EXISTS lg_qa_conversation (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128),
    title VARCHAR(256),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_qa_conversation_project ON lg_qa_conversation(project_id);
CREATE INDEX idx_qa_conversation_session ON lg_qa_conversation(session_id);

-- QA 消息表
CREATE TABLE IF NOT EXISTS lg_qa_message (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES lg_qa_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    evidences TEXT,
    confidence DECIMAL(5,4),
    token_count INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_qa_message_conversation ON lg_qa_message(conversation_id, created_at);

-- QA 反馈表
CREATE TABLE IF NOT EXISTS lg_qa_feedback (
    id VARCHAR(64) PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL REFERENCES lg_qa_message(id) ON DELETE CASCADE,
    conversation_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    helpful BOOLEAN NOT NULL,
    feedback_text TEXT,
    used_evidence_ids TEXT,
    question TEXT,
    answer TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_qa_feedback_message ON lg_qa_feedback(message_id);
CREATE INDEX idx_qa_feedback_project ON lg_qa_feedback(project_id, created_at);
