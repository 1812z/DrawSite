-- 不覆盖已有 admin。
INSERT INTO sys_user (id, username, password_hash, display_name, role, enabled, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000001', 'admin', 'BOOTSTRAP_PENDING',
       '管理员', 'ADMIN', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
