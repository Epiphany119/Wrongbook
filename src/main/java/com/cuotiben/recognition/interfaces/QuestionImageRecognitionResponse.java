package com.cuotiben.recognition.interfaces;

import java.util.List;
import java.util.Map;

/**
 * 拍照识题结构化响应：支持题目图、题答同页图与纯答案页（总答案表）识别，
 * 并保留原始题号列表与原题号->答案/解析映射，便于多图跨页精准对齐后再统一从 1 编号。
 */
public record QuestionImageRecognitionResponse(
        boolean recognized,
        String contentKind,
        String text,
        String questionText,
        String correctAnswer,
        String explanation,
        String answerSource,
        String questionType,
        List<String> suggestedTags,
        List<String> originalNumbers,
        Map<String, String> answerMap,
        Map<String, String> explanationMap,
        String message) {
}
