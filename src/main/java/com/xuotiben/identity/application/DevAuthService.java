package com.xuotiben.identity.application;

import com.xuotiben.common.ApiException;
import com.xuotiben.common.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DevAuthService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final int sessionDays;

    public DevAuthService(JdbcTemplate jdbc,
                          @Value("${app.auth.dev-mode:true}") boolean enabled,
                          @Value("${app.auth.session-days:30}") int sessionDays) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.sessionDays = sessionDays;
    }

    @Transactional
    public Map<String, Object> login(LoginCommand command) {
        if (!enabled) throw new ApiException(HttpStatus.FORBIDDEN, "DEV_LOGIN_DISABLED", "本地开发登录已关闭。");
        if (command == null || command.deviceId() == null || command.deviceId().isBlank()) {
            throw ApiException.badRequest("缺少设备标识。");
        }
        String identityHash = Hashing.sha256(command.deviceId());
        List<String> userIds = jdbc.query(
                "SELECT user_id FROM dev_identities WHERE device_key_hash = ?",
                (rs, row) -> rs.getString(1), identityHash
        );
        String userId;
        if (userIds.isEmpty()) {
            userId = UUID.randomUUID().toString();
            String nickname = cleanNickname(command.nickname());
            jdbc.update("""
                    INSERT INTO users (id, nickname, role, created_at, updated_at)
                    VALUES (?, ?, 'STUDENT', NOW(3), NOW(3))
                    """, userId, nickname);
            jdbc.update("""
                    INSERT INTO dev_identities (device_key_hash, user_id, created_at)
                    VALUES (?, ?, NOW(3))
                    """, identityHash, userId);
        } else {
            userId = userIds.get(0);
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO auth_sessions (token_hash, user_id, expires_at, created_at)
                VALUES (?, ?, ?, NOW(3))
                """, Hashing.sha256(token), userId, LocalDateTime.now().plusDays(sessionDays));

        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT id, nickname, role, grade, semester, textbook_version FROM users WHERE id = ?",
                userId
        );
        return Map.of("token", token, "expiresInDays", sessionDays, "profile", profile);
    }

    private String cleanNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "学生";
        String trimmed = nickname.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    public record LoginCommand(String deviceId, String nickname) {}
}
