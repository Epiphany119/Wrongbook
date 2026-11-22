-- 历史错题组卷记录表：仅存储试卷状态元数据（标题、错题 ID 列表、初始版本与已生成版本标记），
-- 不存储真实 Word 文件，按需实时复原导出（幂等可重复执行）。
USE wrongbook;

CREATE TABLE IF NOT EXISTS practice_papers (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    question_ids JSON NOT NULL,
    record_count INT NOT NULL DEFAULT 0,
    question_count INT NOT NULL DEFAULT 0,
    default_mode VARCHAR(30) NOT NULL DEFAULT 'QUESTIONS_ONLY',
    used_modes JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_practice_papers_owner_time (owner_id, created_at),
    CONSTRAINT fk_practice_paper_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 兼容已建表用户的增量升级：自动补充 default_mode 与 used_modes 字段
SET @has_default_mode = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'practice_papers'
      AND column_name = 'default_mode'
);
SET @sql_default_mode = IF(
    @has_default_mode = 0,
    'ALTER TABLE practice_papers ADD COLUMN default_mode VARCHAR(30) NOT NULL DEFAULT ''QUESTIONS_ONLY'' AFTER question_count',
    'SELECT 1'
);
PREPARE stmt1 FROM @sql_default_mode;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @has_used_modes = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'practice_papers'
      AND column_name = 'used_modes'
);
SET @sql_used_modes = IF(
    @has_used_modes = 0,
    'ALTER TABLE practice_papers ADD COLUMN used_modes JSON NULL AFTER default_mode',
    'SELECT 1'
);
PREPARE stmt2 FROM @sql_used_modes;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

UPDATE practice_papers
SET used_modes = JSON_ARRAY(COALESCE(NULLIF(default_mode, ''), 'QUESTIONS_ONLY'))
WHERE used_modes IS NULL;
