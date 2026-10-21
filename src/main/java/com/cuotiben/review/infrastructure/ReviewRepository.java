package com.cuotiben.review.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findByRequestKey(String ownerId, String requestKey) {
        List<Map<String, Object>> values = jdbc.queryForList("""
                SELECT id, question_id AS questionId, session_id AS sessionId, result,
                       mastery_rating AS masteryRating, confidence, duration_seconds AS durationSeconds,
                       occurred_at AS occurredAt, next_review_at AS nextReviewAt, policy_version AS policyVersion
                FROM review_events WHERE owner_id=? AND request_key=?
                """, ownerId, requestKey);
        return values.isEmpty() ? null : values.get(0);
    }

    public void insert(String id, String ownerId, String questionId, String sessionId, String requestKey,
                       String result, Integer masteryRating, Integer confidence, Integer durationSeconds, long questionVersion,
                       LocalDateTime nextReviewAt, String policyVersion) {
        jdbc.update("""
                INSERT INTO review_events
                    (id, owner_id, question_id, session_id, request_key, result, mastery_rating, confidence, duration_seconds,
                     question_version, occurred_at, next_review_at, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), ?, ?)
                """, id, ownerId, questionId, sessionId, requestKey, result, masteryRating, confidence, durationSeconds,
                questionVersion, nextReviewAt, policyVersion);
    }
}
