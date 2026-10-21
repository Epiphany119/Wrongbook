package com.cuotiben.question.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class QuestionReflectionRepository {
    private final JdbcTemplate jdbc;

    public QuestionReflectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Reflection> list(String questionId, String ownerId) {
        return jdbc.query("""
                SELECT id, part_number AS partNumber, content, created_at AS createdAt
                FROM question_reflections
                WHERE question_id=? AND owner_id=?
                ORDER BY created_at DESC
                """, (rs, row) -> new Reflection(
                rs.getString("id"),
                rs.getObject("partNumber") == null ? null : rs.getInt("partNumber"),
                rs.getString("content"),
                rs.getTimestamp("createdAt").toLocalDateTime().toString()
        ), questionId, ownerId);
    }

    public Reflection create(String id, String questionId, String ownerId, Integer partNumber, String content) {
        jdbc.update("""
                INSERT INTO question_reflections (id, owner_id, question_id, part_number, content, created_at)
                VALUES (?, ?, ?, ?, ?, NOW(3))
                """, id, ownerId, questionId, partNumber, content);
        return jdbc.queryForObject("""
                SELECT id, part_number AS partNumber, content, created_at AS createdAt
                FROM question_reflections WHERE id=? AND owner_id=?
                """, (rs, row) -> new Reflection(
                rs.getString("id"),
                rs.getObject("partNumber") == null ? null : rs.getInt("partNumber"),
                rs.getString("content"),
                rs.getTimestamp("createdAt").toLocalDateTime().toString()
        ), id, ownerId);
    }

    public record Reflection(String id, Integer partNumber, String content, String createdAt) {}
}
