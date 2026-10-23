package com.seekwd.dormcheck.service;

import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Separate cookie and session store for operators; never shares ordinary user sessions. */
@Service
public class AdminSessionService {
    public static final String COOKIE_NAME = "dormcheck_admin_session";
    private final JdbcTemplate jdbc;
    private final SecuritySupport security;
    private final boolean cookieSecure;

    public AdminSessionService(JdbcTemplate jdbc, SecuritySupport security,
        @Value("${dormcheck.cookie-secure}") boolean cookieSecure) {
        this.jdbc = jdbc;
        this.security = security;
        this.cookieSecure = cookieSecure;
    }

    public Optional<Map<String, Object>> find(HttpServletRequest request) {
        String token = cookie(request);
        if (token == null) return Optional.empty();
        var rows = jdbc.query("SELECT s.csrf_token,a.id,a.email,a.display_name,a.role FROM admin_sessions s " +
            "JOIN admin_users a ON a.id=s.admin_user_id WHERE s.token_hash=? AND s.expires_at>?",
            (rs, row) -> Map.<String, Object>of("admin_id", rs.getString("id"), "email", rs.getString("email"),
                "display_name", rs.getString("display_name"), "role", rs.getString("role"), "csrf_token", rs.getString("csrf_token")),
            security.hashToken(token), security.now());
        return rows.stream().findFirst();
    }

    public Map<String, Object> require(HttpServletRequest request) {
        return find(request).orElseThrow(security::unauthorized);
    }

    public String issue(HttpServletResponse response, String adminId, HttpServletRequest request) {
        String token = security.randomToken(32);
        String csrf = security.randomToken(24);
        jdbc.update("INSERT INTO admin_sessions(token_hash,admin_user_id,csrf_token,expires_at) VALUES(?,?,?,?)",
            security.hashToken(token), adminId, csrf, security.sessionExpiry(8));
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token).httpOnly(true).secure(cookieSecure)
            .sameSite("Strict").path("/api").maxAge(8L * 86_400).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return csrf;
    }

    public void requireCsrf(HttpServletRequest request, Map<String, Object> session) {
        String supplied = request.getHeader("X-CSRF-Token");
        String expected = String.valueOf(session.get("csrf_token"));
        if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "csrf validation failed");
        }
    }

    public void revoke(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request);
        if (token != null) jdbc.update("DELETE FROM admin_sessions WHERE token_hash=?", security.hashToken(token));
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(cookieSecure)
            .sameSite("Strict").path("/api").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                if (value != null) return null;
                if (cookie.getValue() == null || cookie.getValue().isBlank()) return null;
                value = cookie.getValue();
            }
        }
        return value;
    }
}
