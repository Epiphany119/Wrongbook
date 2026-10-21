package com.cuotiben.practice.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.common.JsonCodec;
import com.cuotiben.question.domain.QuestionRecord;
import com.cuotiben.question.infrastructure.QuestionRepository;
import com.cuotiben.question.infrastructure.QuestionRepository.QuestionFilter;
import com.cuotiben.review.application.ReviewService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PracticeService {
    private final JdbcTemplate jdbc;
    private final QuestionRepository questions;
    private final ReviewService reviews;
    private final JsonCodec json;

    public PracticeService(JdbcTemplate jdbc, QuestionRepository questions, ReviewService reviews, JsonCodec json) {
        this.jdbc = jdbc;
        this.questions = questions;
        this.reviews = reviews;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> start(String userId, PracticeCommand command) {
        PracticeCommand safe = command == null ? new PracticeCommand(null, null, null, null, false, 10, null) : command;
        int limit = Math.max(1, Math.min(50, safe.limit() == null ? 10 : safe.limit()));
        List<QuestionRecord> selected;
        if (safe.questionId() != null && !safe.questionId().isBlank()) {
            QuestionRecord target = questions.findOwned(safe.questionId(), userId);
            if (target == null) throw ApiException.notFound("错题不存在或已移入回收站。");
            if ("ARCHIVED".equals(target.masteryState())) throw ApiException.conflict("已归档的错题不能练习。");
            selected = List.of(target);
        } else {
            QuestionFilter filter = new QuestionFilter(null, safe.subjectId(), safe.chapterId(), null, null,
                    safe.knowledgePointId(), null, safe.errorReason(), null, safe.dueOnly(), true);
            selected = questions.list(userId, filter, 1, limit, "DUE_SOON");
        }
        if (selected.isEmpty()) return Map.of("sessionId", "", "totalCount", 0, "items", List.of());

        String sessionId = UUID.randomUUID().toString();
        List<String> ids = selected.stream().map(QuestionRecord::id).toList();
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("subjectId", safe.subjectId());
        conditions.put("chapterId", safe.chapterId());
        conditions.put("knowledgePointId", safe.knowledgePointId());
        conditions.put("errorReason", safe.errorReason());
        conditions.put("dueOnly", safe.dueOnly());
        conditions.put("questionId", safe.questionId());
        jdbc.update("""
                INSERT INTO practice_sessions
                    (id, owner_id, session_filters, question_ids, total_count, correct_count, started_at)
                VALUES (?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, 0, NOW(3))
                """, sessionId, userId, json.write(conditions), json.write(ids), ids.size());

        List<PracticeQuestion> items = selected.stream().map(question -> new PracticeQuestion(
                question.id(), question.questionText(), question.subjectName(), question.chapterName(),
                question.ownAnswer(), question.correctAnswer(), question.correction(), question.explanation(),
                question.errorReason(), question.questionType(),
                question.nextReviewAt() == null ? null : question.nextReviewAt().toString(),
                question.version())).toList();
        return Map.of("sessionId", sessionId, "totalCount", items.size(), "items", items);
    }

    @Transactional
    public Map<String, Object> submit(String userId, String sessionId, List<AnswerCommand> answers) {
        if (answers == null || answers.isEmpty()) throw ApiException.badRequest("请先完成至少一道题。");
        Map<String, Object> session = ownedSession(userId, sessionId);
        List<String> questionIds = json.readStrings(String.valueOf(session.get("questionIds")));
        List<Map<String, Object>> results = new ArrayList<>();
        java.util.Set<String> submittedIds = new java.util.HashSet<>();
        for (AnswerCommand answer : answers) {
            if (answer == null || answer.questionId() == null || !questionIds.contains(answer.questionId())) {
                throw ApiException.badRequest("提交内容包含不属于本次练习的题目。");
            }
            if (!submittedIds.add(answer.questionId())) throw ApiException.badRequest("一次提交中不能重复包含同一道题。");
            String requestKey = sessionId + ":" + answer.questionId();
            Map<String, Object> review = reviews.record(userId, answer.questionId(),
                    new ReviewService.ReviewCommand(requestKey, null, answer.rating(), null,
                            answer.durationSeconds()), sessionId);
            QuestionRecord updated = (QuestionRecord) review.get("question");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", updated.id());
            item.put("questionText", updated.questionText());
            item.put("correctAnswer", updated.correctAnswer());
            item.put("correction", updated.correction());
            item.put("explanation", updated.explanation());
            item.put("ownAnswer", updated.ownAnswer());
            item.put("errorReason", updated.errorReason());
            item.put("questionType", updated.questionType());
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) review.get("event");
            item.put("submittedResult", event.get("result"));
            item.put("masteryRating", event.get("masteryRating"));
            item.put("nextReviewAt", updated.nextReviewAt() == null ? null : updated.nextReviewAt().toString());
            results.add(item);
        }
        Integer answered = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT question_id) FROM review_events WHERE session_id=? AND owner_id=?",
                Integer.class, sessionId, userId);
        int total = ((Number) session.get("totalCount")).intValue();
        boolean completed = answered != null && answered >= total;
        Integer correct = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_events WHERE session_id=? AND owner_id=? AND result='CORRECT'",
                Integer.class, sessionId, userId);
        jdbc.update("""
                UPDATE practice_sessions SET correct_count=?, completed_at=IF(?=1, COALESCE(completed_at, NOW(3)), completed_at)
                WHERE id=? AND owner_id=?
                """, correct == null ? 0 : correct, completed ? 1 : 0, sessionId, userId);
        return Map.of("sessionId", sessionId, "completed", completed, "answeredCount",
                answered == null ? 0 : answered, "totalCount", total, "correctCount",
                correct == null ? 0 : correct, "results", results);
    }

    private Map<String, Object> ownedSession(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, question_ids AS questionIds, total_count AS totalCount, completed_at AS completedAt
                FROM practice_sessions WHERE id=? AND owner_id=?
                """, sessionId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("练习记录不存在。");
        return rows.get(0);
    }

    public record PracticeCommand(String subjectId, String chapterId, String knowledgePointId,
                                  String errorReason, Boolean dueOnly, Integer limit, String questionId) {}
    public record AnswersCommand(List<AnswerCommand> answers) {}
    public record AnswerCommand(String questionId, Integer rating, Integer durationSeconds) {}
    public record PracticeQuestion(String id, String questionText, String subjectName, String chapterName,
                                   String ownAnswer, String correctAnswer, String correction, String explanation,
                                   String errorReason, String questionType, String nextReviewAt, long version) {}
}
