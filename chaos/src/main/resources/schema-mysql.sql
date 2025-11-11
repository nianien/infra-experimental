-- ==========================================================
-- 命名空间表：用于数据隔离（租户 / 系统维度）
-- ==========================================================
CREATE TABLE `config_namespace` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '命名空间 ID',
    `NAME` VARCHAR(128) NOT NULL COMMENT '命名空间名称，唯一标识一个系统或租户',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '命名空间描述',
    `OWNER` VARCHAR(128) DEFAULT NULL COMMENT '负责人或创建者',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ns_name` (`NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配置命名空间表';

-- ==========================================================
-- 配置分组表：用于逻辑归类（模块、功能、环境等）
-- ==========================================================
CREATE TABLE `config_group` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置分组 ID',
    `namespace_id` BIGINT NOT NULL COMMENT '所属命名空间 ID',
    `NAME` VARCHAR(128) NOT NULL COMMENT '分组名称（在命名空间内唯一）',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '分组描述（如模块名、功能区等）',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ns_group` (`namespace_id`,`NAME`),
    KEY `idx_group_ns` (`namespace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配置分组表';

-- ==========================================================
-- 配置项表：核心数据表
-- ==========================================================
CREATE TABLE `config_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置项 ID',
    `namespace_id` BIGINT NOT NULL COMMENT '命名空间 ID（为唯一约束冗余存储）',
    `group_id` BIGINT NOT NULL COMMENT '所属分组 ID',
    `key` VARCHAR(255) NOT NULL COMMENT '配置键（全局唯一）',
    `value` LONGTEXT NOT NULL COMMENT '默认配置值（JSON / 文本 / 数值均可）',
    `variant` JSON DEFAULT NULL COMMENT '泳道覆盖配置，如 {"gray": "...", "blue": "..."}',
    `type` VARCHAR(64) DEFAULT 'string' COMMENT '配置值类型（string/json/int 等）',
    `enabled` TINYINT(1) DEFAULT '1' COMMENT '是否启用，1=启用 0=禁用',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '配置项描述',
    `updated_by` VARCHAR(128) DEFAULT NULL COMMENT '最后修改人',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` BIGINT DEFAULT '0' COMMENT '版本号（用于审计/对比）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ns_key` (`namespace_id`,`key`) COMMENT '同一命名空间下 key 全局唯一',
    KEY `idx_ns_group_enabled` (`namespace_id`,`group_id`,`enabled`),
    KEY `idx_item_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配置项表';