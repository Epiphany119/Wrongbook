USE wrongbook;

-- 扩展中文错题全文检索字段。脚本可重复执行。
SET @has_search_index = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'question_records'
      AND index_name = 'ft_question_search'
);
SET @search_index_sql = IF(
    @has_search_index = 0,
    'ALTER TABLE question_records ADD FULLTEXT INDEX ft_question_search (question_text, own_answer, correct_answer, correction, explanation, error_reason, source, question_type) WITH PARSER ngram',
    'SELECT ''ft_question_search already exists'''
);
PREPARE search_index_stmt FROM @search_index_sql;
EXECUTE search_index_stmt;
DEALLOCATE PREPARE search_index_stmt;

-- 新索引覆盖题干后，移除旧的单列全文索引，避免重复维护索引。
SET @has_old_index = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'question_records'
      AND index_name = 'ft_question_text'
);
SET @old_index_sql = IF(
    @has_old_index > 0,
    'ALTER TABLE question_records DROP INDEX ft_question_text',
    'SELECT ''ft_question_text does not exist'''
);
PREPARE old_index_stmt FROM @old_index_sql;
EXECUTE old_index_stmt;
DEALLOCATE PREPARE old_index_stmt;
