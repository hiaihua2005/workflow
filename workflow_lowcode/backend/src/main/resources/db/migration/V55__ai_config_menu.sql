-- ============================================================
-- V55: 系统管理 → 模型配置 菜单
--
-- 大模型动态配置页（OpenAI 兼容端点/API Key/模型，保存即生效）。
-- 目录「系统管理」id=1 下新增菜单（menuType 1, id=320, sort 6，
-- 紧随 字典管理(sort 5) 之后），permission system:aiconfig:list。
-- 组件路径 system/ai-config/AiConfigPage 与前端 /system/ai-config
-- 静态路由对应。
--
-- 【幂等】NOT EXISTS 防御，可重复执行。
-- 【注意】status 显式 1（启用）、is_deleted 0，勿依赖列默认值。
-- ============================================================

-- 1) 菜单：模型配置
INSERT INTO sys_menu (`id`, `created_at`, `updated_at`, `is_deleted`, `component`, `icon`, `menu_name`, `menu_type`, `parent_id`, `path`, `permission`, `sort_order`, `status`)
SELECT 320, NOW(3), NOW(3), 0, 'system/ai-config/AiConfigPage', 'Cpu', '模型配置', 1, 1, '/system/ai-config', 'system:aiconfig:list', 6, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 320);

-- 2) ROLE_ADMIN 授权
INSERT INTO sys_role_menu (`role_id`, `menu_id`)
SELECT 1, 320 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 320);
