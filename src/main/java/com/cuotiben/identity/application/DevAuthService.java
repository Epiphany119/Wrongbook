package com.cuotiben.identity.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.common.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DevAuthService {
    private final JdbcTemplate jdbc;
    private final AuthSessionService sessions;
    private final boolean enabled;

    public DevAuthService(JdbcTemplate jdbc,
                          AuthSessionService sessions,
                          @Value("${app.auth.dev-mode:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.enabled = enabled;
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

        String token = sessions.issue(userId);

        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT id, nickname, role, grade, semester, textbook_version, avatar_storage_key FROM users WHERE id = ?",
                userId
        );
        profile.put("avatarUrl", profile.get("avatar_storage_key") == null ? "" : "/api/v1/me/avatar");
        profile.remove("avatar_storage_key");
        return Map.of("token", token, "expiresInDays", sessions.sessionDays(), "profile", profile);
    }

    private String cleanNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "学生";
        String trimmed = nickname.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    public record LoginCommand(String deviceId, String nickname) {}
}
