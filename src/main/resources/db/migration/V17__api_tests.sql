CREATE TABLE api_test_run (
    id VARCHAR(36) PRIMARY KEY,
    application_id VARCHAR(36) NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total INTEGER NOT NULL,
    passed INTEGER NOT NULL DEFAULT 0,
    completed INTEGER NOT NULL DEFAULT 0,
    report_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_api_test_app ON api_test_run(application_id,created_at DESC);
