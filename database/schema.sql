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
    avatar_storage_key VARCHAR(120) NULL,
    avatar_mime_type VARCHAR(80) NULL,
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
    FULLTEXT KEY ft_question_search
        (question_text, own_answer, correct_answer, correction, explanation, error_reason, source, question_type)
        WITH PARSER ngram,
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
    mastery_rating TINYINT NULL,
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

CREATE TABLE IF NOT EXISTS practice_papers (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    question_ids JSON NOT NULL,
    record_count INT NOT NULL DEFAULT 0,
    question_count INT NOT NULL DEFAULT 0,
    default_mode VARCHAR(30) NOT NULL DEFAULT 'QUESTIONS_ONLY',
    used_modes JSON NOT NULL DEFAULT (JSON_ARRAY('QUESTIONS_ONLY')),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_practice_papers_owner_time (owner_id, created_at),
    CONSTRAINT fk_practice_paper_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
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

INSERT INTO chapters (id, owner_id, subject_id, parent_id, grade, textbook_version, name, sort_order) VALUES
    ('10000000-0000-4000-8000-000000000001', NULL, 'history', NULL, NULL, '通用历史', '先秦与秦汉时期', 10),
    ('10000000-0000-4000-8000-000000000002', NULL, 'history', NULL, NULL, '通用历史', '三国两晋南北朝与隋唐', 20),
    ('10000000-0000-4000-8000-000000000003', NULL, 'history', NULL, NULL, '通用历史', '宋元明清时期', 30),
    ('10000000-0000-4000-8000-000000000004', NULL, 'history', NULL, NULL, '通用历史', '晚清危机与近代化探索', 40),
    ('10000000-0000-4000-8000-000000000005', NULL, 'history', NULL, NULL, '通用历史', '辛亥革命与新民主主义兴起', 50),
    ('10000000-0000-4000-8000-000000000006', NULL, 'history', NULL, NULL, '通用历史', '抗日战争与解放战争', 60),
    ('10000000-0000-4000-8000-000000000007', NULL, 'history', NULL, NULL, '通用历史', '中华人民共和国与改革开放', 70),
    ('10000000-0000-4000-8000-000000000008', NULL, 'history', NULL, NULL, '通用历史', '古代亚非欧文明', 80),
    ('10000000-0000-4000-8000-000000000009', NULL, 'history', NULL, NULL, '通用历史', '走向近代与资本主义制度确立', 90),
    ('10000000-0000-4000-8000-000000000010', NULL, 'history', NULL, NULL, '通用历史', '工业革命与马克思主义诞生', 100),
    ('10000000-0000-4000-8000-000000000011', NULL, 'history', NULL, NULL, '通用历史', '两次世界大战与战后格局', 110),
    ('10000000-0000-4000-8000-000000000012', NULL, 'history', NULL, NULL, '通用历史', '冷战与当代世界发展', 120)
ON DUPLICATE KEY UPDATE name=VALUES(name), sort_order=VALUES(sort_order);

INSERT INTO knowledge_points (id, owner_id, chapter_id, name, sort_order) VALUES
    ('20000000-0000-4000-8000-000000000001', NULL, '10000000-0000-4000-8000-000000000001', '分封制与宗法制', 10),
    ('20000000-0000-4000-8000-000000000002', NULL, '10000000-0000-4000-8000-000000000001', '商鞅变法与百家争鸣', 20),
    ('20000000-0000-4000-8000-000000000003', NULL, '10000000-0000-4000-8000-000000000001', '秦朝中央集权与汉武帝大一统', 30),
    ('20000000-0000-4000-8000-000000000004', NULL, '10000000-0000-4000-8000-000000000002', '北魏孝文帝改革与民族交融', 10),
    ('20000000-0000-4000-8000-000000000005', NULL, '10000000-0000-4000-8000-000000000002', '科举制与三省六部制', 20),
    ('20000000-0000-4000-8000-000000000006', NULL, '10000000-0000-4000-8000-000000000002', '贞观之治与开元盛世', 30),
    ('20000000-0000-4000-8000-000000000007', NULL, '10000000-0000-4000-8000-000000000003', '经济重心南移与行省制度', 10),
    ('20000000-0000-4000-8000-000000000008', NULL, '10000000-0000-4000-8000-000000000003', '明清君主专制强化与军机处', 20),
    ('20000000-0000-4000-8000-000000000009', NULL, '10000000-0000-4000-8000-000000000003', '郑和下西洋与闭关锁国', 30),
    ('20000000-0000-4000-8000-000000000010', NULL, '10000000-0000-4000-8000-000000000004', '鸦片战争与不平等条约', 10),
    ('20000000-0000-4000-8000-000000000011', NULL, '10000000-0000-4000-8000-000000000004', '洋务运动与戊戌变法', 20),
    ('20000000-0000-4000-8000-000000000012', NULL, '10000000-0000-4000-8000-000000000004', '甲午中日战争与八国联军侵华', 30),
    ('20000000-0000-4000-8000-000000000013', NULL, '10000000-0000-4000-8000-000000000005', '辛亥革命与中华民国成立', 10),
    ('20000000-0000-4000-8000-000000000014', NULL, '10000000-0000-4000-8000-000000000005', '新文化运动与五四运动', 20),
    ('20000000-0000-4000-8000-000000000015', NULL, '10000000-0000-4000-8000-000000000005', '中国共产党诞生与工农武装割据', 30),
    ('20000000-0000-4000-8000-000000000016', NULL, '10000000-0000-4000-8000-000000000006', '全民族抗战与抗战胜利意义', 10),
    ('20000000-0000-4000-8000-000000000017', NULL, '10000000-0000-4000-8000-000000000006', '三大战役与渡江战役', 20),
    ('20000000-0000-4000-8000-000000000018', NULL, '10000000-0000-4000-8000-000000000007', '三大改造与一五计划', 10),
    ('20000000-0000-4000-8000-000000000019', NULL, '10000000-0000-4000-8000-000000000007', '十一届三中全会与改革开放', 20),
    ('20000000-0000-4000-8000-000000000020', NULL, '10000000-0000-4000-8000-000000000007', '新中国外交成就与一国两制', 30),
    ('20000000-0000-4000-8000-000000000021', NULL, '10000000-0000-4000-8000-000000000008', '雅典民主政治与罗马法', 10),
    ('20000000-0000-4000-8000-000000000022', NULL, '10000000-0000-4000-8000-000000000008', '西欧庄园与中世纪城市大学', 20),
    ('20000000-0000-4000-8000-000000000023', NULL, '10000000-0000-4000-8000-000000000009', '文艺复兴与新航路开辟', 10),
    ('20000000-0000-4000-8000-000000000024', NULL, '10000000-0000-4000-8000-000000000009', '英美法资产阶级革命与宪政文件', 20),
    ('20000000-0000-4000-8000-000000000025', NULL, '10000000-0000-4000-8000-000000000010', '两次工业革命与世界市场形成', 10),
    ('20000000-0000-4000-8000-000000000026', NULL, '10000000-0000-4000-8000-000000000010', '马克思主义诞生与巴黎公社', 20),
    ('20000000-0000-4000-8000-000000000027', NULL, '10000000-0000-4000-8000-000000000011', '一战、十月革命与凡尔赛-华盛顿体系', 10),
    ('20000000-0000-4000-8000-000000000028', NULL, '10000000-0000-4000-8000-000000000011', '罗斯福新政与第二次世界大战', 20),
    ('20000000-0000-4000-8000-000000000029', NULL, '10000000-0000-4000-8000-000000000012', '美苏冷战与两极格局演变', 10),
    ('20000000-0000-4000-8000-000000000030', NULL, '10000000-0000-4000-8000-000000000012', '世界多极化与经济全球化', 20)
ON DUPLICATE KEY UPDATE name=VALUES(name), sort_order=VALUES(sort_order);
