CREATE TABLE conversation_context (
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    memory_enabled BOOLEAN NOT NULL,
    covered_run_id VARCHAR(36) NOT NULL,
    summary TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, memory_enabled)
);
