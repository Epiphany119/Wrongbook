package com.cuotiben.identity.application;

import com.cuotiben.common.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AuthSessionService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final int sessionDays;

    public AuthSessionService(JdbcTemplate jdbc,
                              @Value("${app.auth.session-days:30}") int sessionDays) {
        this.jdbc = jdbc;
        this.sessionDays = sessionDays;
    }

    public String issue(String userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO auth_sessions (token_hash, user_id, expires_at, created_at)
                VALUES (?, ?, ?, NOW(3))
                """, Hashing.sha256(token), userId, LocalDateTime.now().plusDays(sessionDays));
        return token;
    }

    public int sessionDays() {
        return sessionDays;
    }
}
