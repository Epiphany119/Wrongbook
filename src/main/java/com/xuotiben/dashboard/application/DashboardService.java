package com.xuotiben.dashboard.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview(String userId, int days) {
        int due = number("""
                SELECT COUNT(*) FROM question_records
                WHERE owner_id=? AND deleted_at IS NULL AND mastery_state <> 'ARCHIVED'
                  AND next_review_at <= NOW(3)
                """, userId);
        int overdue = number("""
                SELECT COUNT(*) FROM question_records
                WHERE owner_id=? AND deleted_at IS NULL AND mastery_state <> 'ARCHIVED'
                  AND next_review_at < CURDATE()
                """, userId);
        int total = number("""
                SELECT COUNT(*) FROM review_events WHERE owner_id=? AND occurred_at >= DATE_SUB(NOW(3), INTERVAL ? DAY)
                """, userId, days);
        int correct = number("""
                SELECT COUNT(*) FROM review_events
                WHERE owner_id=? AND result='CORRECT' AND occurred_at >= DATE_SUB(NOW(3), INTERVAL ? DAY)
                """, userId, days);
        int repeated = number("""
                SELECT COUNT(*) FROM (
                    SELECT question_id FROM review_events
                    WHERE owner_id=? AND result IN ('INCORRECT','UNCERTAIN')
                    GROUP BY question_id HAVING COUNT(*) >= 2
                ) repeated_questions
                """, userId);
        Double accuracy = total == 0 ? null : Math.round(correct * 1000.0 / total) / 10.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dueCount", due);
        result.put("overdueCount", overdue);
        result.put("reviewSampleCount", total);
        result.put("correctCount", correct);
        result.put("accuracyPercent", accuracy);
        result.put("repeatedErrorCount", repeated);
        result.put("rangeDays", days);
        result.put("subjectTrends", jdbc.queryForList("""
                SELECT s.id AS subjectId, s.name AS subjectName, COUNT(r.id) AS sampleCount,
                       SUM(CASE WHEN r.result='CORRECT' THEN 1 ELSE 0 END) AS correctCount
                FROM review_events r
                JOIN question_records q ON q.id=r.question_id
                LEFT JOIN subjects s ON s.id=q.subject_id
                WHERE r.owner_id=? AND r.occurred_at >= DATE_SUB(NOW(3), INTERVAL ? DAY)
                GROUP BY s.id, s.name ORDER BY sampleCount DESC
                """, userId, days).stream().map(row -> {
            Map<String, Object> trend = new LinkedHashMap<>(row);
            int samples = ((Number) row.get("sampleCount")).intValue();
            int right = ((Number) row.get("correctCount")).intValue();
            trend.put("accuracyPercent", samples == 0 ? null : Math.round(right * 1000.0 / samples) / 10.0);
            return trend;
        }).toList());
        return result;
    }

    private int number(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
