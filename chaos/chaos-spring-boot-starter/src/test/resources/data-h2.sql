-- ==========================================================
-- 初始化数据
-- ==========================================================
MERGE INTO config_item (
    namespace,
    group_name,
    "key",
    "value",
    enabled
    )
    KEY (namespace, group_name, "key")
    VALUES
    ('com.ddm', 'cfd', 'demo.name', 'TestUser', 1),
    ('com.ddm', 'cfd', 'demo.age',  '25',       1);

