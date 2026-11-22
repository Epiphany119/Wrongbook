package com.cuotiben.recognition.interfaces;

import java.util.List;

/** Structured draft returned by image recognition; answers never need to be embedded in question text. */
public record QuestionImageRecognitionResponse(
        boolean recognized,
        String text,
        String questionText,
        String correctAnswer,
        String explanation,
        String answerSource,
        String questionType,
        List<String> suggestedTags,
        String message) {
}
