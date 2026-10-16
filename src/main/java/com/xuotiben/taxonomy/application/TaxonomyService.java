package com.xuotiben.taxonomy.application;

import com.xuotiben.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaxonomyService {
    private final JdbcTemplate jdbc;

    public TaxonomyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> subjects() {
        return jdbc.queryForList("SELECT id, name, sort_order AS sortOrder FROM subjects WHERE enabled = 1 ORDER BY sort_order, name");
    }

    public List<Map<String, Object>> chapters(String userId, String subjectId, String grade) {
        if (grade == null || grade.isBlank()) {
            return jdbc.queryForList("""
                    SELECT id, subject_id AS subjectId, parent_id AS parentId, grade, name
                    FROM chapters WHERE subject_id = ? AND (owner_id IS NULL OR owner_id=?)
                    ORDER BY sort_order, name
                    """, subjectId, userId);
        }
        return jdbc.queryForList("""
                SELECT id, subject_id AS subjectId, parent_id AS parentId, grade, name
                FROM chapters WHERE subject_id = ? AND (owner_id IS NULL OR owner_id=?)
                  AND (grade = ? OR grade IS NULL)
                ORDER BY sort_order, name
                """, subjectId, userId, grade);
    }

    public List<Map<String, Object>> knowledgePoints(String userId, String chapterId) {
        return jdbc.queryForList("""
                SELECT k.id, k.chapter_id AS chapterId, k.name
                FROM knowledge_points k JOIN chapters c ON c.id=k.chapter_id
                WHERE k.chapter_id = ? AND (c.owner_id IS NULL OR c.owner_id=?)
                  AND (k.owner_id IS NULL OR k.owner_id=?)
                ORDER BY k.sort_order, k.name
                """, chapterId, userId, userId);
    }

    @Transactional
    public Map<String, Object> createChapter(String userId, ChapterCommand command) {
        if (command == null || command.subjectId() == null || command.name() == null || command.name().isBlank()) {
            throw ApiException.badRequest("请填写学科和章节名称。");
        }
        String name = command.name().trim();
        if (name.length() > 120) throw ApiException.badRequest("章节名称不能超过120个字符。");
        Integer subjectCount = jdbc.queryForObject("SELECT COUNT(*) FROM subjects WHERE id=? AND enabled=1",
                Integer.class, command.subjectId());
        if (subjectCount == null || subjectCount == 0) throw ApiException.badRequest("所选学科不存在。");
        String parentId = blankToNull(command.parentId());
        if (parentId != null) {
            Integer parentCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chapters WHERE id=? AND subject_id=? AND (owner_id IS NULL OR owner_id=?)
                    """, Integer.class, parentId, command.subjectId(), userId);
            if (parentCount == null || parentCount == 0) throw ApiException.badRequest("上级章节不属于当前学科。");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO chapters (id, owner_id, subject_id, parent_id, grade, textbook_version, name, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1000)
                """, id, userId, command.subjectId(), parentId, blankToNull(command.grade()),
                blankToNull(command.textbookVersion()), name);
        return Map.of("id", id, "subjectId", command.subjectId(), "parentId",
                parentId == null ? "" : parentId, "name", name);
    }

    @Transactional
    public Map<String, Object> createKnowledgePoint(String userId, KnowledgePointCommand command) {
        if (command == null || command.chapterId() == null || command.name() == null || command.name().isBlank()) {
            throw ApiException.badRequest("请填写章节和知识点名称。");
        }
        String name = command.name().trim();
        if (name.length() > 120) throw ApiException.badRequest("知识点名称不能超过120个字符。");
        Integer chapterCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chapters WHERE id=? AND (owner_id IS NULL OR owner_id=?)
                """, Integer.class, command.chapterId(), userId);
        if (chapterCount == null || chapterCount == 0) throw ApiException.notFound("章节不存在。");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_points (id, owner_id, chapter_id, name, sort_order)
                VALUES (?, ?, ?, ?, 1000)
                """, id, userId, command.chapterId(), name);
        return Map.of("id", id, "chapterId", command.chapterId(), "name", name);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ChapterCommand(String subjectId, String parentId, String grade, String textbookVersion, String name) {}
    public record KnowledgePointCommand(String chapterId, String name) {}
}
