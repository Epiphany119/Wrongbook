-- 练习掌握度五档评分；可重复执行。
USE wrongbook;

SET @has_mastery_rating = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'review_events'
      AND column_name = 'mastery_rating'
);
SET @update_sql = IF(
    @has_mastery_rating = 0,
    'ALTER TABLE review_events ADD COLUMN mastery_rating TINYINT NULL AFTER result',
    'SELECT 1'
);
PREPARE update_statement FROM @update_sql;
EXECUTE update_statement;
DEALLOCATE PREPARE update_statement;
