package com.xuotiben.question.domain;

public final class ReviewSchedulePolicy {
    public static final String VERSION = "interval-v1";
    private static final int[] INTERVALS = {1, 3, 7, 14};

    private ReviewSchedulePolicy() {}

    public static Schedule next(int currentLevel, int currentStreak, ReviewResult result) {
        int level;
        int streak;
        if (result == ReviewResult.CORRECT) {
            streak = currentStreak + 1;
            level = Math.min(INTERVALS.length - 1, currentLevel + 1);
        } else {
            streak = 0;
            level = 0;
        }
        String state = result == ReviewResult.CORRECT && streak >= 3 ? "MASTERED" : "REVIEW";
        return new Schedule(level, streak, INTERVALS[level], state, VERSION);
    }

    public record Schedule(int intervalLevel, int consecutiveCorrect, int days, String state, String policyVersion) {}
}
