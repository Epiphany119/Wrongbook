package com.xuotiben.profile.application;

import com.xuotiben.common.ApiException;
import com.xuotiben.common.JsonCodec;
import com.xuotiben.storage.application.AttachmentStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final AttachmentStorageService attachmentStorage;

    public ProfileService(JdbcTemplate jdbc, JsonCodec json, AttachmentStorageService attachmentStorage) {
        this.jdbc = jdbc;
        this.json = json;
        this.attachmentStorage = attachmentStorage;
    }

    public Map<String, Object> get(String userId) {
        return jdbc.query("SELECT id, nickname, role, grade, semester, textbook_version, preferred_subjects, created_at "
                        + "FROM users WHERE id = ?",
                rs -> {
                    if (!rs.next()) throw ApiException.notFound("用户档案不存在。");
                    return Map.of(
                            "id", rs.getString("id"),
                            "nickname", rs.getString("nickname"),
                            "role", rs.getString("role"),
                            "grade", rs.getString("grade") == null ? "" : rs.getString("grade"),
                            "semester", rs.getString("semester") == null ? "" : rs.getString("semester"),
                            "textbookVersion", rs.getString("textbook_version") == null ? "" : rs.getString("textbook_version"),
                            "preferredSubjects", json.readStrings(rs.getString("preferred_subjects")),
                            "createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString()
                    );
                }, userId);
    }

    @Transactional
    public Map<String, Object> update(String userId, ProfileCommand command) {
        if (command == null) throw ApiException.badRequest("档案内容不能为空。");
        String nickname = command.nickname() == null ? "学生" : command.nickname().trim();
        if (nickname.isBlank()) nickname = "学生";
        if (nickname.length() > 40) throw ApiException.badRequest("昵称不能超过40个字符。");
        jdbc.update("""
                UPDATE users SET nickname = ?, grade = ?, semester = ?, textbook_version = ?,
                    preferred_subjects = ?, updated_at = NOW(3)
                WHERE id = ?
                """,
                nickname, blankToNull(command.grade()), blankToNull(command.semester()),
                blankToNull(command.textbookVersion()), json.write(command.preferredSubjects() == null
                        ? List.of() : command.preferredSubjects()), userId);
        return get(userId);
    }

    public Map<String, Object> export(String userId) {
        return Map.of(
                "exportedAt", java.time.LocalDateTime.now().toString(),
                "profile", get(userId),
                "questions", jdbc.queryForList("""
                        SELECT id, question_text, own_answer, correct_answer, correction, explanation, subject_id,
                               chapter_id, knowledge_point_ids, tags, error_reason, mastery_state, source,
                               question_date, difficulty, question_type, next_review_at, review_interval_level,
                               consecutive_correct, version, created_at, updated_at
                        FROM question_records WHERE owner_id = ? AND deleted_at IS NULL
                        ORDER BY created_at DESC
                        """, userId),
                "reviewHistory", jdbc.queryForList("""
                        SELECT r.id, r.question_id, r.result, r.confidence, r.duration_seconds, r.occurred_at,
                               r.next_review_at, r.policy_version
                        FROM review_events r JOIN question_records q ON q.id = r.question_id
                        WHERE q.owner_id = ? ORDER BY r.occurred_at DESC
                        """, userId),
                "attachments", jdbc.queryForList("""
                        SELECT id, question_id AS questionId, file_name AS fileName, mime_type AS mimeType,
                               byte_size AS byteSize, CONCAT('/api/v1/attachments/', id) AS fileUrl
                        FROM question_attachments WHERE owner_id=? ORDER BY created_at
                        """, userId)
        );
    }

    @Transactional
    public void deleteAccount(String userId) {
        attachmentStorage.deleteAllForOwner(userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public record ProfileCommand(String nickname, String grade, String semester,
                                 String textbookVersion, List<String> preferredSubjects) {}
}
