-- 学生练习随笔：每次保存均新增一条记录，保留历史反思。
CREATE TABLE IF NOT EXISTS question_reflections (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    part_number INT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_reflection_question_time (question_id, created_at),
    KEY idx_reflection_owner_time (owner_id, created_at),
    CONSTRAINT fk_reflection_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_reflection_question FOREIGN KEY (question_id) REFERENCES question_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
