-- ============================================================
-- V41: 审批意见表增加手写签名列
-- 配合节点 signature.enabled / useLast / allowUpload 配置：
--   - completeTask 提交时把 body.signature（dataURL）随 approve 意见落库
--   - useLast=true 的节点，getTaskDetail 查该用户最近一条签名回填
-- ============================================================

-- 【幂等】MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故沿用 V28 的
-- information_schema 动态判断写法：列已存在（ddl-auto=update 已建）则跳过，
-- 同时兼容 MariaDB（PREPARE/EXECUTE 两边都支持）与生产全新库。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'wf_task_comment'
      AND column_name = 'signature'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wf_task_comment ADD COLUMN signature LONGTEXT NULL COMMENT ''手写签名 dataURL（signature.enabled 节点提交时存储，action=approve）'' AFTER comment',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
