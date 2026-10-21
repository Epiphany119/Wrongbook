package com.cuotiben.question.domain;

import com.cuotiben.common.ApiException;

public enum ReviewResult {
    CORRECT, PARTIAL, INCORRECT, UNCERTAIN;

    public static ReviewResult fromMasteryRating(int rating) {
        if (rating < 1 || rating > 5) throw ApiException.badRequest("掌握度评分范围为1到5。");
        if (rating >= 4) return CORRECT;
        if (rating == 3) return PARTIAL;
        return INCORRECT;
    }

    public static ReviewResult parse(String value) {
        if (value == null) throw ApiException.badRequest("请选择本次复习结果。");
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("复习结果不合法。");
        }
    }
}
