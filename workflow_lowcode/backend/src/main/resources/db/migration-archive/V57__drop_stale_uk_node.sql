-- ============================================================
-- V57: 清理残留的过窄唯一键 uk_node —— V48 的幂等兜底
--
-- 【问题】
--   部分环境 wf_node_config 上仍残留 V4/基线时代的窄唯一键
--   `uk_node (tenant_id, process_def_id, node_id)`，而 flyway 历史已标记
--   V48 执行过 ⇒ V48 的 DROP 不会重跑。该约束与"编辑态行(process_definition_id
--   IS NULL) + 各部署版本快照行并存"的数据模型冲突，导致保存/部署时报：
--     Duplicate entry 'default-<draftId>-<nodeId>' for key 'wf_node_config.uk_node'
--   典型来源：从携带 uk_node 的历史 baseline dump 重放（其 flyway 历史已含 V48）。
--
-- 【修复】
--   仅做删除，幂等可重复执行；正确约束 uk_node_version 已由 V48/实体 @Table 保证。
--   MariaDB 唯一索引把 NULL 视作互不相同 ⇒ 编辑态行不受约束，单行不变量由
--   ProcessDesignService.saveDesign 的 delete+insert 保证（同 V48 设计语义）。
-- ============================================================

SET @ddl = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE wf_node_config DROP INDEX uk_node',
              'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_node_config' AND INDEX_NAME = 'uk_node'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
