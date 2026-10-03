CREATE TABLE application_plugin (
    application_id VARCHAR(36) NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    plugin_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(application_id, plugin_id)
);
