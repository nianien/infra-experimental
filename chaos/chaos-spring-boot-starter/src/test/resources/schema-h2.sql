CREATE TABLE config_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace    VARCHAR(255) NOT NULL,
    group_name   VARCHAR(255) NOT NULL,
    "key"        VARCHAR(255) NOT NULL,
    "value"      TEXT NOT NULL,
    variants     TEXT,
    type         VARCHAR(64) DEFAULT 'string',
    enabled      TINYINT DEFAULT 1,
    description  VARCHAR(255),
    updated_by   VARCHAR(64),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT DEFAULT 0,
    CONSTRAINT uk_ns_gp_key UNIQUE (namespace, group_name, "key")
);