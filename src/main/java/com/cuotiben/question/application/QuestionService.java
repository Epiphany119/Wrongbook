package com.cuotiben.question.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.common.JsonCodec;
import com.cuotiben.question.domain.QuestionRecord;
import com.cuotiben.question.infrastructure.QuestionRepository;
import com.cuotiben.question.infrastructure.QuestionReflectionRepository;
import com.cuotiben.question.infrastructure.QuestionReflectionRepository.Reflection;
import com.cuotiben.question.infrastructure.QuestionRepository.QuestionFilter;
import com.cuotiben.question.infrastructure.QuestionRepository.QuestionRecordCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuestionService {
    private final QuestionRepository questions;
    private final QuestionReflectionRepository reflections;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public QuestionService(QuestionRepository questions, QuestionReflectionRepository reflections,
                           JdbcTemplate jdbc, JsonCodec json) {
        this.questions = questions;
        this.reflections = reflections;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> list(String userId, String keyword, String subjectId, String chapterId,
                                    String dateFrom, String dateTo, String knowledgePointId, String tag, String errorReason, String state,
                                    Boolean dueOnly, String sort, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        validateDate(dateFrom);
        validateDate(dateTo);
        QuestionFilter filter = new QuestionFilter(keyword, subjectId, chapterId, dateFrom, dateTo, knowledgePointId,
                tag, errorReason, state, dueOnly);
        return Map.of("items", questions.list(userId, filter, safePage, safeSize, sort),
                "page", safePage, "pageSize", safeSize, "total", questions.count(userId, filter));
    }

    public List<QuestionRecord> trash(String userId) {
        return questions.trash(userId);
    }

    public Map<String, Object> detail(String userId, String id) {
        QuestionRecord question = questions.findOwned(id, userId);
        if (question == null) throw ApiException.notFound("错题不存在或已移入回收站。");
        return Map.of(
                "question", question,
                "attachments", jdbc.queryForList("""
                        SELECT id, file_name AS fileName, mime_type AS mimeType, byte_size AS byteSize,
                               CONCAT('/api/v1/attachments/', id) AS fileUrl, created_at AS createdAt
                        FROM question_attachments WHERE question_id=? AND owner_id=? ORDER BY created_at
                        """, id, userId),
                "timeline", jdbc.queryForList("""
                        SELECT id, result, confidence, duration_seconds AS durationSeconds,
                               occurred_at AS occurredAt, next_review_at AS nextReviewAt,
                               policy_version AS policyVersion
                        FROM review_events WHERE question_id=? AND owner_id=? ORDER BY occurred_at DESC
                        """, id, userId),
                "reflections", reflections.list(id, userId)
        );
    }

    public List<Reflection> reflections(String userId, String id) {
        require(id, userId);
        return reflections.list(id, userId);
    }

    @Transactional
    public Reflection addReflection(String userId, String id, ReflectionCommand command) {
        require(id, userId);
        if (command == null || command.content() == null || command.content().isBlank()) {
            throw ApiException.badRequest("请先写下新的思考或反思。");
        }
        String content = command.content().trim();
        if (content.length() > 4000) throw ApiException.badRequest("随笔不能超过4000个字符。");
        Integer partNumber = command.partNumber();
        if (partNumber != null && (partNumber < 1 || partNumber > 500)) {
            throw ApiException.badRequest("题号范围不正确。");
        }
        return reflections.create(UUID.randomUUID().toString(), id, userId, partNumber, content);
    }

    @Transactional
    public QuestionRecord create(String userId, QuestionCommand command) {
        StoredCommand stored = validate(command);
        if (stored.idempotencyKey() != null && !stored.idempotencyKey().isBlank()) {
            String existingId = questions.idByClientRequest(userId, stored.idempotencyKey());
            if (existingId != null) return require(existingId, userId);
        }
        validateTaxonomy(stored, userId);
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        questions.create(stored, userId, id, "NEW", now.plusDays(1));
        return require(id, userId);
    }

    @Transactional
    public QuestionRecord update(String userId, String id, QuestionCommand command) {
        QuestionRecord current = require(id, userId);
        if (command == null || command.version() == null) {
            throw ApiException.badRequest("编辑时缺少记录版本号。");
        }
        if (current.version() != command.version()) {
            throw ApiException.conflict("这条错题已在其他位置更新，请重新加载后再编辑。");
        }
        StoredCommand stored = validate(command);
        validateTaxonomy(stored, userId);
        if (!questions.update(id, userId, command.version(), stored)) {
            throw ApiException.conflict("这条错题已在其他位置更新，请重新加载后再编辑。");
        }
        return require(id, userId);
    }

    @Transactional
    public void delete(String userId, String id) {
        require(id, userId);
        questions.softDelete(id, userId);
    }

    @Transactional
    public QuestionRecord archive(String userId, String id) {
        require(id, userId);
        questions.archive(id, userId);
        return require(id, userId);
    }

    @Transactional
    public QuestionRecord restore(String userId, String id) {
        QuestionRecord deleted = questions.findOwnedIncludingDeleted(id, userId);
        if (deleted == null) throw ApiException.notFound("回收站中没有这条错题。");
        questions.restore(id, userId);
        return require(id, userId);
    }

    private QuestionRecord require(String id, String userId) {
        QuestionRecord record = questions.findOwned(id, userId);
        if (record == null) throw ApiException.notFound("错题不存在或已移入回收站。");
        return record;
    }

    private StoredCommand validate(QuestionCommand command) {
        if (command == null || command.questionText() == null || command.questionText().isBlank()) {
            throw ApiException.badRequest("请填写题目内容。");
        }
        if (command.questionText().length() > 12000) throw ApiException.badRequest("题目内容不能超过12000个字符。");
        if (command.subjectId() == null || command.subjectId().isBlank()) throw ApiException.badRequest("请选择学科。");
        if (command.difficulty() != null && (command.difficulty() < 1 || command.difficulty() > 5)) {
            throw ApiException.badRequest("难度范围为1到5。");
        }
        LocalDate date = null;
        if (command.questionDate() != null && !command.questionDate().isBlank()) {
            try {
                date = LocalDate.parse(command.questionDate());
            } catch (Exception exception) {
                throw ApiException.badRequest("题目日期格式应为YYYY-MM-DD。");
            }
        }
        return new StoredCommand(
                clean(command.idempotencyKey()), command.questionText().trim(), clean(command.ownAnswer()),
                clean(command.correctAnswer()), clean(command.correction()), clean(command.explanation()),
                command.subjectId(), clean(command.chapterId()),
                json.write(command.knowledgePointIds() == null ? List.of() : command.knowledgePointIds()),
                json.write(command.tags() == null ? List.of() : command.tags().stream()
                        .filter(tag -> tag != null && !tag.isBlank()).map(String::trim).distinct().limit(20).toList()),
                clean(command.errorReason()), clean(command.source()), date, command.difficulty(),
                clean(command.questionType())
        );
    }

    private void validateTaxonomy(StoredCommand command, String userId) {
        Integer subjectCount = jdbc.queryForObject("SELECT COUNT(*) FROM subjects WHERE id=? AND enabled=1",
                Integer.class, command.subjectId());
        if (subjectCount == null || subjectCount == 0) throw ApiException.badRequest("所选学科不存在。");
        if (command.chapterId() != null) {
            Integer chapterCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chapters WHERE id=? AND subject_id=? AND (owner_id IS NULL OR owner_id=?)
                    """, Integer.class, command.chapterId(), command.subjectId(), userId);
            if (chapterCount == null || chapterCount == 0) throw ApiException.badRequest("所选章节与学科不匹配。");
        }
        List<String> pointIds = json.readStrings(command.knowledgePointIdsJson());
        if (!pointIds.isEmpty()) {
            if (command.chapterId() == null) throw ApiException.badRequest("选择知识点前请先选择章节。");
            String placeholders = String.join(",", java.util.Collections.nCopies(pointIds.size(), "?"));
            List<Object> args = new java.util.ArrayList<>();
            args.add(command.chapterId());
            args.addAll(pointIds.stream().distinct().toList());
            args.add(userId);
            Integer pointCount = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_points WHERE chapter_id=? AND id IN ("
                    + placeholders + ") AND (owner_id IS NULL OR owner_id=?)", Integer.class, args.toArray());
            if (pointCount == null || pointCount != pointIds.stream().distinct().count()) {
                throw ApiException.badRequest("知识点与所选章节不匹配。");
            }
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateDate(String value) {
        if (value == null || value.isBlank()) return;
        try {
            LocalDate.parse(value);
        } catch (Exception exception) {
            throw ApiException.badRequest("筛选日期格式应为YYYY-MM-DD。");
        }
    }

    public record QuestionCommand(
            String idempotencyKey,
            String questionText,
            String ownAnswer,
            String correctAnswer,
            String correction,
            String explanation,
            String subjectId,
            String chapterId,
            List<String> knowledgePointIds,
            List<String> tags,
            String errorReason,
            String source,
            String questionDate,
            Integer difficulty,
            String questionType,
            Long version
    ) {}

    public record ReflectionCommand(String content, Integer partNumber) {}

    private record StoredCommand(
            String idempotencyKey,
            String questionText,
            String ownAnswer,
            String correctAnswer,
            String correction,
            String explanation,
            String subjectId,
            String chapterId,
            String knowledgePointIdsJson,
            String tagsJson,
            String errorReason,
            String source,
            LocalDate questionDate,
            Integer difficulty,
            String questionType
    ) implements QuestionRecordCommand {}
}
