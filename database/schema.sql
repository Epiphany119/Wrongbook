-- 错题本首版 MySQL 8 初始化脚本
-- 请在 MySQL 客户端中运行本文件，然后按 README 配置后端数据库账号。

CREATE DATABASE IF NOT EXISTS wrongbook
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE wrongbook;

CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) NOT NULL PRIMARY KEY,
    nickname VARCHAR(40) NOT NULL DEFAULT '学生',
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    grade VARCHAR(30) NULL,
    semester VARCHAR(30) NULL,
    textbook_version VARCHAR(100) NULL,
    preferred_subjects JSON NOT NULL DEFAULT (JSON_ARRAY()),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS dev_identities (
    device_key_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_dev_identity_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_auth_sessions_user (user_id),
    KEY idx_auth_sessions_expiry (expires_at),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS subjects (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chapters (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NULL,
    subject_id VARCHAR(40) NOT NULL,
    parent_id CHAR(36) NULL,
    grade VARCHAR(30) NULL,
    textbook_version VARCHAR(100) NULL,
    name VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    KEY idx_chapters_owner (owner_id),
    KEY idx_chapters_subject (subject_id, grade, sort_order),
    CONSTRAINT fk_chapter_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_chapter_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CONSTRAINT fk_chapter_parent FOREIGN KEY (parent_id) REFERENCES chapters(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_points (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NULL,
    chapter_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    KEY idx_knowledge_owner (owner_id),
    KEY idx_knowledge_chapter (chapter_id, sort_order),
    CONSTRAINT fk_knowledge_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS question_records (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    client_request_id VARCHAR(80) NULL,
    question_text TEXT NOT NULL,
    own_answer TEXT NULL,
    correct_answer TEXT NULL,
    correction TEXT NULL,
    explanation TEXT NULL,
    subject_id VARCHAR(40) NOT NULL,
    chapter_id CHAR(36) NULL,
    knowledge_point_ids JSON NOT NULL DEFAULT (JSON_ARRAY()),
    tags JSON NOT NULL DEFAULT (JSON_ARRAY()),
    error_reason VARCHAR(100) NULL,
    mastery_state VARCHAR(20) NOT NULL DEFAULT 'NEW',
    source VARCHAR(160) NULL,
    question_date DATE NULL,
    difficulty TINYINT NULL,
    question_type VARCHAR(40) NULL,
    next_review_at DATETIME(3) NOT NULL,
    review_interval_level TINYINT NOT NULL DEFAULT 0,
    consecutive_correct INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    UNIQUE KEY uq_question_client_request (owner_id, client_request_id),
    KEY idx_question_owner_state (owner_id, mastery_state, deleted_at),
    KEY idx_question_owner_due (owner_id, next_review_at),
    KEY idx_question_owner_subject (owner_id, subject_id),
    FULLTEXT KEY ft_question_text (question_text),
    CONSTRAINT fk_question_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_question_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CONSTRAINT fk_question_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS question_attachments (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    client_request_id VARCHAR(80) NULL,
    file_name VARCHAR(180) NOT NULL,
    mime_type VARCHAR(80) NOT NULL,
    byte_size BIGINT NOT NULL,
    storage_key VARCHAR(80) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uq_attachment_client_request (owner_id, client_request_id),
    KEY idx_attachment_question (question_id),
    KEY idx_attachment_owner (owner_id),
    CONSTRAINT fk_attachment_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachment_question FOREIGN KEY (question_id) REFERENCES question_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS practice_sessions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    session_filters JSON NOT NULL,
    question_ids JSON NOT NULL,
    total_count INT NOT NULL,
    correct_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    KEY idx_practice_owner_started (owner_id, started_at),
    CONSTRAINT fk_practice_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS review_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    session_id CHAR(36) NULL,
    request_key VARCHAR(120) NOT NULL,
    result VARCHAR(20) NOT NULL,
    confidence TINYINT NULL,
    duration_seconds INT NULL,
    question_version BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    next_review_at DATETIME(3) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    UNIQUE KEY uq_review_request (owner_id, request_key),
    KEY idx_review_owner_time (owner_id, occurred_at),
    KEY idx_review_question_time (question_id, occurred_at),
    KEY idx_review_session (session_id),
    CONSTRAINT fk_review_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_question FOREIGN KEY (question_id) REFERENCES question_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_session FOREIGN KEY (session_id) REFERENCES practice_sessions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS feedback_records (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    category VARCHAR(40) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_feedback_owner_time (owner_id, created_at),
    CONSTRAINT fk_feedback_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO subjects (id, name, sort_order, enabled) VALUES
    ('chinese', '语文', 1, 1),
    ('math', '数学', 2, 1),
    ('english', '英语', 3, 1),
    ('physics', '物理', 4, 1),
    ('chemistry', '化学', 5, 1),
    ('biology', '生物', 6, 1),
    ('history', '历史', 7, 1),
    ('geography', '地理', 8, 1),
    ('politics', '道德与法治', 9, 1)
ON DUPLICATE KEY UPDATE name=VALUES(name), sort_order=VALUES(sort_order), enabled=VALUES(enabled);
