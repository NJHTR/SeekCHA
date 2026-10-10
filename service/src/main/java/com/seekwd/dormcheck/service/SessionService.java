package com.seekwd.dormcheck.service;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.SecuritySupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import com.seekwd.dormcheck.mapper.SessionMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class SessionService {
    public static final String COOKIE_NAME = "dormcheck_session";
    private final SessionMapper sessionMapper;
    private final SecuritySupport security;
    private final DeviceService devices;
    private final boolean cookieSecure;

    public SessionService(SessionMapper sessionMapper, SecuritySupport security, DeviceService devices, @Value("${dormcheck.cookie-secure}") boolean cookieSecure) {
        this.sessionMapper = sessionMapper;
        this.security = security;
        this.devices = devices;
        this.cookieSecure = cookieSecure;
    }

    public Optional<Map<String, Object>> find(HttpServletRequest request) {
        String token = cookie(request);
        if (token == null) return Optional.empty();
        var row = sessionMapper.findActive(security.hashToken(token), security.now());
        if (row == null) return Optional.empty();
        return Optional.of(Map.of("user_id", row.userId, "csrf_token", row.csrfToken,
            "email", row.email, "display_name", row.displayName,
            "current_device_id", row.currentDeviceId == null ? "" : row.currentDeviceId));
    }

    public Map<String, Object> require(HttpServletRequest request) {
        return find(request).orElseThrow(security::unauthorized);
    }

    public String issue(HttpServletResponse response, String userId, HttpServletRequest request) {
        String token = security.randomToken(32);
        String csrf = security.randomToken(24);
        String tokenHash = security.hashToken(token);
        String deviceId = devices.register(request, userId);
        sessionMapper.insert(tokenHash, userId, csrf, security.sessionExpiry(14));
        if (deviceId != null) sessionMapper.insertDeviceSession(tokenHash, deviceId);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/api").maxAge(14L * 86_400).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return csrf;
    }

    public String currentDeviceId(HttpServletRequest request, String userId) {
        return devices.currentId(request, userId);
    }

    public void requireCsrf(HttpServletRequest request, Map<String, Object> session) {
        String token = request.getHeader("X-CSRF-Token");
        String expected = (String) session.get("csrf_token");
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "csrf validation failed");
        }
    }

    public String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        }
        return null;
    }

    public void revoke(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request);
        if (token != null) sessionMapper.deleteByTokenHash(security.hashToken(token));
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(cookieSecure)
            .sameSite("Strict").path("/api").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
