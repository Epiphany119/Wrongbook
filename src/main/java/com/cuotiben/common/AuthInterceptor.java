package com.cuotiben.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;

    public AuthInterceptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (request.getRequestURI().equals("/api/v1/health")
                || request.getRequestURI().equals("/api/v1/auth/dev-login")) return true;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录状态已失效，请重新进入小程序。");
        }
        String tokenHash = Hashing.sha256(header.substring(7).trim());
        List<String> users = jdbc.query(
                "SELECT user_id FROM auth_sessions WHERE token_hash = ? AND expires_at > NOW(3)",
                (rs, row) -> rs.getString(1), tokenHash
        );
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录状态已失效，请重新进入小程序。");
        }
        request.setAttribute("userId", users.get(0));
        return true;
    }
}
