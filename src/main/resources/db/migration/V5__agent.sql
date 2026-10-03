-- 基础对话任务与用户/应用持久记忆
CREATE TABLE agent_run (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL REFERENCES application(id),
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversation(id),
    user_message_id VARCHAR(36) NOT NULL REFERENCES conversation_message(id),
    assistant_message_id VARCHAR(36) REFERENCES conversation_message(id),
    model_id VARCHAR(64) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    reasoning_effort VARCHAR(16) NOT NULL,
    memory_enabled BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX idx_agent_active_conversation ON agent_run(conversation_id) WHERE status = 'RUNNING';
CREATE INDEX idx_agent_conversation_created ON agent_run(conversation_id, created_at);

CREATE TABLE agent_memory (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
    application_id VARCHAR(36) REFERENCES application(id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('USER', 'APPLICATION')),
    content VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((scope = 'USER' AND application_id IS NULL) OR (scope = 'APPLICATION' AND application_id IS NOT NULL))
);
CREATE INDEX idx_memory_owner_application ON agent_memory(owner_user_id, application_id, updated_at);
