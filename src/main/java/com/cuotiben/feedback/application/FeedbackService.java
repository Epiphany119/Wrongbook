package com.cuotiben.feedback.application;

import com.cuotiben.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FeedbackService {
    private final JdbcTemplate jdbc;

    public FeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void submit(String userId, FeedbackCommand command) {
        if (command == null || command.content() == null || command.content().isBlank()) {
            throw ApiException.badRequest("请填写反馈内容。");
        }
        String content = command.content().trim();
        if (content.length() > 4000) throw ApiException.badRequest("反馈内容不能超过4000个字符。");
        String category = command.category() == null || command.category().isBlank()
                ? "SUGGESTION" : command.category().trim().toUpperCase();
        jdbc.update("""
                INSERT INTO feedback_records (id, owner_id, category, content, created_at)
                VALUES (?, ?, ?, ?, NOW(3))
                """, UUID.randomUUID().toString(), userId, category, content);
    }

    public record FeedbackCommand(String category, String content) {}
}
