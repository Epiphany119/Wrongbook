package com.xuotiben.review.application;

import com.xuotiben.common.ApiException;
import com.xuotiben.question.domain.QuestionRecord;
import com.xuotiben.question.domain.ReviewResult;
import com.xuotiben.question.domain.ReviewSchedulePolicy;
import com.xuotiben.question.infrastructure.QuestionRepository;
import com.xuotiben.review.infrastructure.ReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {
    private final QuestionRepository questions;
    private final ReviewRepository reviews;
    private final JdbcTemplate jdbc;

    public ReviewService(QuestionRepository questions, ReviewRepository reviews, JdbcTemplate jdbc) {
        this.questions = questions;
        this.reviews = reviews;
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> record(String userId, String questionId, ReviewCommand command, String sessionId) {
        if (command == null) throw ApiException.badRequest("复习内容不能为空。");
        ReviewResult result = ReviewResult.parse(command.result());
        String requestKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : command.idempotencyKey().trim();
        if (requestKey.length() > 120) throw ApiException.badRequest("复习请求标识过长。");
        Map<String, Object> previous = reviews.findByRequestKey(userId, requestKey);
        if (previous != null) {
            if (!questionId.equals(String.valueOf(previous.get("questionId")))) {
                throw ApiException.conflict("复习请求标识已用于其他题目。");
            }
            if (!result.name().equals(String.valueOf(previous.get("result")))
                    || !sameNumber(previous.get("confidence"), command.confidence())
                    || !sameNumber(previous.get("durationSeconds"), command.durationSeconds())
                    || !sameId(previous.get("sessionId"), sessionId)) {
                throw ApiException.conflict("复习请求标识已用于不同的提交内容。");
            }
            return response(previous, requireQuestion(questionId, userId));
        }

        QuestionRecord question = requireQuestion(questionId, userId);
        if ("ARCHIVED".equals(question.masteryState())) throw ApiException.conflict("已归档的错题不能复习。");
        if (command.confidence() != null && (command.confidence() < 1 || command.confidence() > 5)) {
            throw ApiException.badRequest("信心评分范围为1到5。");
        }
        if (command.durationSeconds() != null
                && (command.durationSeconds() < 0 || command.durationSeconds() > 86400)) {
            throw ApiException.badRequest("答题耗时不合法。");
        }
        ReviewSchedulePolicy.Schedule schedule = ReviewSchedulePolicy.next(
                question.reviewIntervalLevel(), question.consecutiveCorrect(), result);
        LocalDateTime nextReviewAt = LocalDateTime.now().plusDays(schedule.days());
        int updated = jdbc.update("""
                UPDATE question_records SET mastery_state=?, review_interval_level=?, consecutive_correct=?,
                    next_review_at=?, version=version+1, updated_at=NOW(3)
                WHERE id=? AND owner_id=? AND version=? AND deleted_at IS NULL
                """, schedule.state(), schedule.intervalLevel(), schedule.consecutiveCorrect(), nextReviewAt,
                questionId, userId, question.version());
        if (updated != 1) throw ApiException.conflict("错题已更新，请刷新后重新记录复习结果。");

        String eventId = UUID.randomUUID().toString();
        reviews.insert(eventId, userId, questionId, sessionId, requestKey, result.name(), command.confidence(),
                command.durationSeconds(), question.version() + 1, nextReviewAt, schedule.policyVersion());
        Map<String, Object> event = reviews.findByRequestKey(userId, requestKey);
        return response(event, requireQuestion(questionId, userId));
    }

    private QuestionRecord requireQuestion(String questionId, String userId) {
        QuestionRecord question = questions.findOwned(questionId, userId);
        if (question == null) throw ApiException.notFound("错题不存在或已移入回收站。");
        return question;
    }

    private Map<String, Object> response(Map<String, Object> event, QuestionRecord question) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", event);
        result.put("question", question);
        return result;
    }

    private boolean sameNumber(Object stored, Integer requested) {
        if (stored == null || requested == null) return stored == null && requested == null;
        return stored instanceof Number number && number.intValue() == requested;
    }

    private boolean sameId(Object stored, String requested) {
        if (stored == null || requested == null) return stored == null && requested == null;
        return requested.equals(String.valueOf(stored));
    }

    public record ReviewCommand(String idempotencyKey, String result, Integer confidence, Integer durationSeconds) {}
}
