package com.xuotiben.question.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record QuestionRecord(
        String id,
        String ownerId,
        String questionText,
        String ownAnswer,
        String correctAnswer,
        String correction,
        String explanation,
        String subjectId,
        String subjectName,
        String chapterId,
        String chapterName,
        List<String> knowledgePointIds,
        List<String> tags,
        String errorReason,
        String masteryState,
        String source,
        LocalDate questionDate,
        Integer difficulty,
        String questionType,
        LocalDateTime nextReviewAt,
        int reviewIntervalLevel,
        int consecutiveCorrect,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int reviewCount
) {}
