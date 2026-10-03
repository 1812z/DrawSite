-- 用户与平台角色
CREATE TABLE sys_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 首次启动由 Java 生成 BCrypt 随机密码；占位账号不能登录。
INSERT INTO sys_user (id, username, password_hash, display_name, role, enabled, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin', 'BOOTSTRAP_PENDING',
        '管理员', 'ADMIN', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
