CREATE TABLE agent_clarification (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    questions_json TEXT NOT NULL,
    answers_json TEXT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ANSWERED', 'CANCELLED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX idx_clarification_pending ON agent_clarification(run_id) WHERE status = 'PENDING';
CREATE INDEX idx_clarification_conversation ON agent_clarification(conversation_id, created_at);
