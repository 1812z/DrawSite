-- 平台配置与默认值
CREATE TABLE platform_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value JSONB NOT NULL,
    description VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(36) REFERENCES sys_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('registration.enabled', 'true', '是否允许新用户注册'),
    ('ai.models', '[]', '可用模型配置，凭据仅保存引用'),
    ('ai.defaultModelId', 'null', '默认模型标识');
