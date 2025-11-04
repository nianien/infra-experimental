-- ==========================================================
-- 初始化数据
-- ==========================================================
INSERT INTO `config_namespace` (`name`, `description`)
VALUES ('com.ddm', 'Demo namespace')
    ON DUPLICATE KEY UPDATE `description`='Demo namespace';

INSERT INTO `config_group` (`namespace_id`, `name`, `description`)
VALUES (
           (SELECT `id` FROM `config_namespace` WHERE `name`='com.ddm'),
           'cfd',
           'Default test group'
       )
    ON DUPLICATE KEY UPDATE `description`='Default test group';

INSERT INTO `config_item` (`namespace_id`, `group_id`, `key`, `value`, `enabled`)
VALUES (
           (SELECT `id` FROM `config_namespace` WHERE `name`='com.ddm'),
           (SELECT `id` FROM `config_group` WHERE `name`='cfd' AND `namespace_id`=(SELECT `id` FROM `config_namespace` WHERE `name`='com.ddm')),
           'demo.name',
           'TestUser',
           1
       )
    ON DUPLICATE KEY UPDATE `value`='TestUser';

INSERT INTO `config_item` (`namespace_id`, `group_id`, `key`, `value`, `enabled`)
VALUES (
           (SELECT `id` FROM `config_namespace` WHERE `name`='com.ddm'),
           (SELECT `id` FROM `config_group` WHERE `name`='cfd' AND `namespace_id`=(SELECT `id` FROM `config_namespace` WHERE `name`='com.ddm')),
           'demo.age',
           '25',
           1
       )
    ON DUPLICATE KEY UPDATE `value`='25';