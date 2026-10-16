-- 已有错题本数据库增量更新脚本：支持个人头像上传。
-- 可重复执行；已存在的头像字段会自动跳过。
USE wrongbook;

SET @has_avatar_storage_key = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'avatar_storage_key'
);
SET @update_sql = IF(
    @has_avatar_storage_key = 0,
    'ALTER TABLE users ADD COLUMN avatar_storage_key VARCHAR(120) NULL',
    'SELECT 1'
);
PREPARE update_statement FROM @update_sql;
EXECUTE update_statement;
DEALLOCATE PREPARE update_statement;

SET @has_avatar_mime_type = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'avatar_mime_type'
);
SET @update_sql = IF(
    @has_avatar_mime_type = 0,
    'ALTER TABLE users ADD COLUMN avatar_mime_type VARCHAR(80) NULL',
    'SELECT 1'
);
PREPARE update_statement FROM @update_sql;
EXECUTE update_statement;
DEALLOCATE PREPARE update_statement;
