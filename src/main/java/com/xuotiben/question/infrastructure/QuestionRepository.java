package com.xuotiben.question.infrastructure;

import com.xuotiben.common.JsonCodec;
import com.xuotiben.question.domain.QuestionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class QuestionRepository {
    private static final String SELECT_COLUMNS = """
            SELECT q.id, q.owner_id, q.question_text, q.own_answer, q.correct_answer, q.correction, q.explanation,
                   q.subject_id, s.name AS subject_name, q.chapter_id, c.name AS chapter_name,
                   q.knowledge_point_ids, q.tags, q.error_reason, q.mastery_state, q.source, q.question_date,
                   q.difficulty, q.question_type, q.next_review_at, q.review_interval_level,
                   q.consecutive_correct, q.version, q.created_at, q.updated_at,
                   (SELECT COUNT(*) FROM review_events r WHERE r.question_id = q.id) AS review_count
            FROM question_records q
            LEFT JOIN subjects s ON s.id = q.subject_id
            LEFT JOIN chapters c ON c.id = q.chapter_id
            """;

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public QuestionRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public QuestionRecord findOwned(String id, String ownerId) {
        List<QuestionRecord> records = jdbc.query(SELECT_COLUMNS
                        + " WHERE q.id = ? AND q.owner_id = ? AND q.deleted_at IS NULL",
                mapper(), id, ownerId);
        return records.isEmpty() ? null : records.get(0);
    }

    public QuestionRecord findOwnedIncludingDeleted(String id, String ownerId) {
        List<QuestionRecord> records = jdbc.query(SELECT_COLUMNS + " WHERE q.id = ? AND q.owner_id = ?",
                mapper(), id, ownerId);
        return records.isEmpty() ? null : records.get(0);
    }

    public List<QuestionRecord> list(String ownerId, QuestionFilter filter, int page, int pageSize, String sort) {
        SqlFilter sql = buildFilter(ownerId, filter, false);
        String order = switch (sort == null ? "" : sort.toUpperCase()) {
            case "DUE_SOON" -> "q.next_review_at ASC, q.created_at DESC";
            case "MOST_WRONG" -> "(SELECT COUNT(*) FROM review_events re WHERE re.question_id=q.id AND re.result IN ('INCORRECT','UNCERTAIN')) DESC, q.updated_at DESC";
            default -> "q.created_at DESC";
        };
        List<Object> args = new ArrayList<>(sql.args());
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        return jdbc.query(SELECT_COLUMNS + sql.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                mapper(), args.toArray());
    }

    public List<QuestionRecord> trash(String ownerId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE q.owner_id = ? AND q.deleted_at IS NOT NULL ORDER BY q.updated_at DESC",
                mapper(), ownerId);
    }

    public long count(String ownerId, QuestionFilter filter) {
        SqlFilter sql = buildFilter(ownerId, filter, false);
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM question_records q" + sql.where(), Long.class, sql.args().toArray());
        return value == null ? 0 : value;
    }

    public long create(QuestionRecordCommand command, String ownerId, String id, String state, LocalDateTime nextReviewAt) {
        return jdbc.update("""
                INSERT INTO question_records
                    (id, owner_id, client_request_id, question_text, own_answer, correct_answer, correction,
                     explanation, subject_id, chapter_id, knowledge_point_ids, tags, error_reason, mastery_state,
                     source, question_date, difficulty, question_type, next_review_at, review_interval_level,
                     consecutive_correct, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, 0, 0, 1, NOW(3), NOW(3))
                """, id, ownerId, command.idempotencyKey(), command.questionText(), command.ownAnswer(),
                command.correctAnswer(), command.correction(), command.explanation(), command.subjectId(),
                command.chapterId(), command.knowledgePointIdsJson(), command.tagsJson(), command.errorReason(),
                state, command.source(), command.questionDate() == null ? null : Date.valueOf(command.questionDate()),
                command.difficulty(), command.questionType(), Timestamp.valueOf(nextReviewAt));
    }

    public boolean update(String id, String ownerId, long expectedVersion, QuestionRecordCommand command) {
        return jdbc.update("""
                UPDATE question_records SET question_text=?, own_answer=?, correct_answer=?, correction=?,
                    explanation=?, subject_id=?, chapter_id=?, knowledge_point_ids=CAST(? AS JSON), tags=CAST(? AS JSON),
                    error_reason=?, source=?, question_date=?, difficulty=?, question_type=?, version=version+1,
                    updated_at=NOW(3)
                WHERE id=? AND owner_id=? AND version=? AND deleted_at IS NULL
                """, command.questionText(), command.ownAnswer(), command.correctAnswer(), command.correction(),
                command.explanation(), command.subjectId(), command.chapterId(), command.knowledgePointIdsJson(),
                command.tagsJson(), command.errorReason(), command.source(),
                command.questionDate() == null ? null : Date.valueOf(command.questionDate()), command.difficulty(),
                command.questionType(), id, ownerId, expectedVersion) == 1;
    }

    public boolean existsByClientRequest(String ownerId, String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_records WHERE owner_id=? AND client_request_id=?",
                Integer.class, ownerId, requestId);
        return count != null && count > 0;
    }

    public String idByClientRequest(String ownerId, String requestId) {
        List<String> ids = jdbc.query("SELECT id FROM question_records WHERE owner_id=? AND client_request_id=?",
                (rs, row) -> rs.getString(1), ownerId, requestId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void softDelete(String id, String ownerId) {
        jdbc.update("UPDATE question_records SET deleted_at=NOW(3), updated_at=NOW(3), version=version+1 "
                + "WHERE id=? AND owner_id=? AND deleted_at IS NULL", id, ownerId);
    }

    public void restore(String id, String ownerId) {
        jdbc.update("UPDATE question_records SET deleted_at=NULL, updated_at=NOW(3), version=version+1 "
                + "WHERE id=? AND owner_id=?", id, ownerId);
    }

    public void archive(String id, String ownerId) {
        jdbc.update("UPDATE question_records SET mastery_state='ARCHIVED', updated_at=NOW(3), version=version+1 "
                + "WHERE id=? AND owner_id=? AND deleted_at IS NULL", id, ownerId);
    }

    public boolean owns(String id, String ownerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_records WHERE id=? AND owner_id=? AND deleted_at IS NULL",
                Integer.class, id, ownerId);
        return count != null && count == 1;
    }

    private SqlFilter buildFilter(String ownerId, QuestionFilter filter, boolean includeDeleted) {
        StringBuilder where = new StringBuilder(" WHERE q.owner_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (!includeDeleted) where.append(" AND q.deleted_at IS NULL");
        if (filter == null || filter.state() == null || !"ARCHIVED".equalsIgnoreCase(filter.state())) {
            where.append(" AND q.mastery_state <> 'ARCHIVED'");
        }
        if (filter != null) {
            if (notBlank(filter.keyword())) {
                where.append(" AND (q.question_text LIKE ? OR q.own_answer LIKE ?)");
                args.add("%" + filter.keyword().trim() + "%");
                args.add("%" + filter.keyword().trim() + "%");
            }
            if (notBlank(filter.subjectId())) { where.append(" AND q.subject_id=?"); args.add(filter.subjectId()); }
            if (notBlank(filter.chapterId())) { where.append(" AND q.chapter_id=?"); args.add(filter.chapterId()); }
            if (notBlank(filter.dateFrom())) { where.append(" AND q.question_date>=?"); args.add(filter.dateFrom()); }
            if (notBlank(filter.dateTo())) { where.append(" AND q.question_date<=?"); args.add(filter.dateTo()); }
            if (notBlank(filter.knowledgePointId())) {
                where.append(" AND JSON_CONTAINS(q.knowledge_point_ids, JSON_QUOTE(?))");
                args.add(filter.knowledgePointId());
            }
            if (notBlank(filter.tag())) {
                where.append(" AND JSON_CONTAINS(q.tags, JSON_QUOTE(?))");
                args.add(filter.tag());
            }
            if (notBlank(filter.errorReason())) { where.append(" AND q.error_reason=?"); args.add(filter.errorReason()); }
            if (notBlank(filter.state())) { where.append(" AND q.mastery_state=?"); args.add(filter.state().toUpperCase()); }
            if (Boolean.TRUE.equals(filter.dueOnly())) where.append(" AND q.next_review_at <= NOW(3)");
        }
        return new SqlFilter(where.toString(), args);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private RowMapper<QuestionRecord> mapper() {
        return (rs, row) -> new QuestionRecord(
                rs.getString("id"), rs.getString("owner_id"), rs.getString("question_text"),
                rs.getString("own_answer"), rs.getString("correct_answer"), rs.getString("correction"),
                rs.getString("explanation"), rs.getString("subject_id"), rs.getString("subject_name"),
                rs.getString("chapter_id"), rs.getString("chapter_name"),
                json.readStrings(rs.getString("knowledge_point_ids")), json.readStrings(rs.getString("tags")),
                rs.getString("error_reason"), rs.getString("mastery_state"), rs.getString("source"),
                localDate(rs, "question_date"), nullableInteger(rs, "difficulty"),
                rs.getString("question_type"), localDateTime(rs, "next_review_at"),
                rs.getInt("review_interval_level"), rs.getInt("consecutive_correct"), rs.getLong("version"),
                localDateTime(rs, "created_at"), localDateTime(rs, "updated_at"), rs.getInt("review_count")
        );
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record SqlFilter(String where, List<Object> args) {}

    public record QuestionFilter(String keyword, String subjectId, String chapterId, String dateFrom, String dateTo,
                                 String knowledgePointId, String tag, String errorReason, String state, Boolean dueOnly) {}

    public interface QuestionRecordCommand {
        String idempotencyKey();
        String questionText();
        String ownAnswer();
        String correctAnswer();
        String correction();
        String explanation();
        String subjectId();
        String chapterId();
        String knowledgePointIdsJson();
        String tagsJson();
        String errorReason();
        String source();
        LocalDate questionDate();
        Integer difficulty();
        String questionType();
    }
}
