package com.cuotiben.recognition.interfaces;

/** Structured draft returned by image recognition; answers never need to be embedded in question text. */
public record QuestionImageRecognitionResponse(
        boolean recognized,
        String text,
        String questionText,
        String correctAnswer,
        String explanation,
        String answerSource,
        String message) {
}
