-- 历史错题组卷记录表：保存试卷名称、关联错题 ID 列表与实际小题数，支持按名检索及多版本重复导出（幂等可执行）
USE wrongbook;

CREATE TABLE IF NOT EXISTS practice_papers (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    question_ids JSON NOT NULL,
    record_count INT NOT NULL DEFAULT 0,
    question_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_practice_papers_owner_time (owner_id, created_at),
    CONSTRAINT fk_practice_paper_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
