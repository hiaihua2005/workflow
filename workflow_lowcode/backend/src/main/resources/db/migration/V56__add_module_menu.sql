-- ============================================================
-- V56: 模块管理菜单
--
-- 「系统管理」下新增「模块管理」菜单（menuType 1, permission module:manage,
-- path /module, 组件 module/ModuleListPage）。
-- 【幂等】NOT EXISTS 防御，可重复执行。
-- 【注意】status 显式 1（启用）、is_deleted 0，勿依赖列默认值。
-- ============================================================

-- 1) 子菜单：模块管理（挂在系统管理 1 下）
INSERT INTO sys_menu (`id`, `created_at`, `updated_at`, `is_deleted`, `component`, `icon`, `menu_name`, `menu_type`, `parent_id`, `path`, `permission`, `sort_order`, `status`)
SELECT 172, NOW(3), NOW(3), 0, 'module/ModuleListPage', 'Box', '模块管理', 1, 1, '/module', 'module:manage', 9, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 172);

-- 2) ROLE_ADMIN 授权
INSERT INTO sys_role_menu (`role_id`, `menu_id`)
SELECT 1, m.menu_id FROM (SELECT 172 AS menu_id) m
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu r WHERE r.role_id = 1 AND r.menu_id = m.menu_id);
