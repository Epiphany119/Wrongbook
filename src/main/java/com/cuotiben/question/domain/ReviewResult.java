package com.cuotiben.question.domain;

import com.cuotiben.common.ApiException;

public enum ReviewResult {
    CORRECT, PARTIAL, INCORRECT, UNCERTAIN;

    public static ReviewResult parse(String value) {
        if (value == null) throw ApiException.badRequest("请选择本次复习结果。");
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("复习结果不合法。");
        }
    }
}
