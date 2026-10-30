CREATE TABLE infrastructure_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    content TEXT NOT NULL
);
CREATE TABLE docker_template (
    id VARCHAR(64) PRIMARY KEY,
    content TEXT NOT NULL
);
CREATE TABLE managed_container (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
    application_id VARCHAR(36) NOT NULL REFERENCES application(id),
    template_id VARCHAR(64) NOT NULL,
    docker_id VARCHAR(100),
    name VARCHAR(100) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    internal_port INTEGER NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    error VARCHAR(500)
);
CREATE INDEX idx_container_owner ON managed_container(owner_user_id);
CREATE TABLE application_deployment (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
    container_id VARCHAR(36) NOT NULL UNIQUE REFERENCES managed_container(id),
    hostname VARCHAR(253) NOT NULL UNIQUE,
    dns_record_id VARCHAR(100),
    status VARCHAR(24) NOT NULL,
    error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
