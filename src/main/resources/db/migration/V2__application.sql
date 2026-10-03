-- 应用与源码版本
CREATE TABLE application (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL CHECK (status IN ('INITIALIZING', 'READY', 'FAILED')),
    current_commit VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_application_owner_updated ON application(owner_user_id, updated_at DESC, id);

CREATE TABLE application_revision (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    commit_hash VARCHAR(40) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (application_id, commit_hash)
);
