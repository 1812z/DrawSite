DROP TABLE agent_memory;
CREATE TABLE user_memory (
    owner_user_id VARCHAR(36) PRIMARY KEY REFERENCES sys_user(id) ON DELETE CASCADE,
    content VARCHAR(4000) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
