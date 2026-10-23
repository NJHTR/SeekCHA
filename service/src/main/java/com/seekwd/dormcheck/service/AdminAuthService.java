package com.seekwd.dormcheck.service;

import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.CredentialCipher;
import com.seekwd.dormcheck.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Service
public class AdminAuthService {
    private final JdbcTemplate jdbc;
    private final SecuritySupport security;
    private final CredentialCipher cipher;
    private final AdminSessionService sessions;
    private final RateLimiter limiter;
    private final JavaMailSender mail;
    private final String bootstrapToken;
    private final String mailFrom;
    private final List<Network> allowedNetworks;

    public AdminAuthService(JdbcTemplate jdbc, SecuritySupport security, CredentialCipher cipher,
        AdminSessionService sessions, RateLimiter limiter, JavaMailSender mail,
        @Value("${dormcheck.admin-bootstrap-token:}") String bootstrapToken,
        @Value("${dormcheck.mail-from:}") String mailFrom,
        @Value("${dormcheck.admin-allowed-cidrs:127.0.0.1/32,::1/128,192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10}") String allowedCidrs) {
        this.jdbc = jdbc;
        this.security = security;
        this.cipher = cipher;
        this.sessions = sessions;
        this.limiter = limiter;
        this.mail = mail;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken.trim();
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.allowedNetworks = Arrays.stream(allowedCidrs.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(Network::parse).toList();
    }

    public Map<String, Object> session(HttpServletRequest request) {
        requirePrivateNetwork(request);
        return sessions.find(request).map(value -> Map.<String, Object>of("authenticated", true,
            "csrf_token", value.get("csrf_token"), "admin", Map.of("id", value.get("admin_id"),
                "email", value.get("email"), "name", value.get("display_name"), "role", value.get("role"))))
            .orElseGet(() -> Map.of("authenticated", false));
    }

    public Map<String, Object> bootstrap(String token, String email, String name, String password, HttpServletRequest request) {
        requirePrivateNetwork(request);
        limiter.check("admin-bootstrap", request.getRemoteAddr(), normalize(email), 5, 900);
        if (bootstrapToken.isBlank() || !constantEquals(bootstrapToken, token)) throw notFound();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Integer.class) > 0) throw new ApiException(HttpStatus.CONFLICT, "管理员初始化已完成");
        String normalized = normalize(email);
        if (!normalized.matches("^[^@\\s]{1,120}@gmail\\.com$")) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "管理员邮箱格式不受支持");
        if (password == null || password.length() < 16 || password.length() > 256) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "管理员密码至少需要 16 位");
        String displayName = name == null || name.isBlank() ? "系统管理员" : name.trim();
        byte[] secret = security.randomBytes(20);
        var digest = security.passwordDigest(password);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO admin_users(id,email,display_name,password_salt,password_hash,role,totp_secret_ciphertext,totp_enabled,created_at) VALUES(?,?,?,?,?,'owner',?,?,?)",
            id, normalized, displayName, digest.salt(), digest.hash(), cipher.encrypt(secret), 0, security.now());
        recordAudit(id, "admin.bootstrap", request);
        return Map.of("created", true, "admin_id", id, "email", normalized);
    }

    public void requestLoginCode(String email, String password, HttpServletRequest request) {
        requirePrivateNetwork(request);
        String normalized = normalize(email);
        limiter.check("admin-login-code-global-window", "global", "all", 10, 900);
        limiter.check("admin-login-code-email", "global", normalized, 3, 900);
        limiter.check("admin-login-code", request.getRemoteAddr(), normalized, 3, 900);
        limiter.checkFailureBackoff("admin-login-failure", request.getRemoteAddr(), normalized, 2, 10, 900, 86_400);
        var row = findAdmin(normalized);
        if (row == null || !security.verifyPassword(password == null ? "" : password, (byte[]) row.get("salt"), (byte[]) row.get("hash"))) {
            limiter.recordFailure("admin-login-failure", request.getRemoteAddr(), normalized, 86_400);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员邮箱或密码不正确");
        }
        String code = String.format(java.util.Locale.ROOT, "%06d", Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(security.randomBytes(4)).getInt()) % 1_000_000);
        jdbc.update("INSERT INTO admin_email_codes(email,code_hash,expires_at,attempts) VALUES(?,?,?,0) ON CONFLICT(email) DO UPDATE SET code_hash=excluded.code_hash,expires_at=excluded.expires_at,attempts=0",
            normalized, security.hashToken(code), Instant.now().plusSeconds(600).toString());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!mailFrom.isBlank()) message.setFrom(mailFrom);
            message.setTo(normalized);
            message.setSubject("SeekCHA 管理员登录验证码");
            message.setText("你的管理员登录验证码是 " + code + "，10 分钟内有效。若非本人操作，请立即修改管理员密码。");
            mail.send(message);
        } catch (Exception error) {
            jdbc.update("DELETE FROM admin_email_codes WHERE email=?", normalized);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "验证码邮件发送失败，请稍后重试");
        }
    }

    public Map<String, Object> login(String email, String password, String code, HttpServletRequest request, HttpServletResponse response) {
        requirePrivateNetwork(request);
        String normalized = normalize(email);
        limiter.check("admin-login-ip", request.getRemoteAddr(), normalized, 8, 900);
        limiter.checkFailureBackoff("admin-login-failure", request.getRemoteAddr(), normalized, 2, 10, 900, 86_400);
        var row = findAdmin(normalized);
        if (row == null || !security.verifyPassword(password == null ? "" : password, (byte[]) row.get("salt"), (byte[]) row.get("hash"))) {
            limiter.recordFailure("admin-login-failure", request.getRemoteAddr(), normalized, 86_400);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员邮箱或密码不正确");
        }
        limiter.check("admin-login-verify", request.getRemoteAddr(), normalized, 10, 900);
        verifyEmailCode(normalized, code, request);
        limiter.clearFailures("admin-login-failure", request.getRemoteAddr(), normalized);
        jdbc.update("UPDATE admin_users SET last_login_at=? WHERE id=?", security.now(), row.get("id"));
        String csrf = sessions.issue(response, (String) row.get("id"), request);
        return Map.of("authenticated", true, "csrf_token", csrf, "admin", Map.of("id", row.get("id"), "email", normalized,
            "name", row.get("name"), "role", row.get("role")));
    }

    private Map<String, Object> findAdmin(String email) {
        return jdbc.query("SELECT id,display_name,role,password_salt,password_hash FROM admin_users WHERE email=?",
            (rs, index) -> Map.<String, Object>of("id", rs.getString("id"), "name", rs.getString("display_name"), "role", rs.getString("role"),
                "salt", rs.getBytes("password_salt"), "hash", rs.getBytes("password_hash")), email).stream().findFirst().orElse(null);
    }

    private void verifyEmailCode(String email, String code, HttpServletRequest request) {
        if (code == null || !code.matches("\\d{6}")) throw new ApiException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
        var rows = jdbc.query("SELECT code_hash,expires_at,attempts FROM admin_email_codes WHERE email=?", (rs, index) -> Map.of("hash", rs.getString("code_hash"), "expires", rs.getString("expires_at"), "attempts", rs.getInt("attempts")), email);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
        var row = rows.get(0);
        if ((int) row.get("attempts") >= 5 || Instant.parse((String) row.get("expires")).isBefore(Instant.now())) {
            jdbc.update("DELETE FROM admin_email_codes WHERE email=?", email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
        }
        jdbc.update("UPDATE admin_email_codes SET attempts=attempts+1 WHERE email=?", email);
        if (!MessageDigest.isEqual(security.hashToken(code).getBytes(StandardCharsets.UTF_8), ((String) row.get("hash")).getBytes(StandardCharsets.UTF_8))) {
            limiter.recordFailure("admin-login-failure", request.getRemoteAddr(), email, 86_400);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
        }
        jdbc.update("DELETE FROM admin_email_codes WHERE email=?", email);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) { sessions.revoke(request, response); }
    public Map<String, Object> require(HttpServletRequest request) { return sessions.require(request); }
    public void requireCsrf(HttpServletRequest request, Map<String, Object> session) { sessions.requireCsrf(request, session); }

    public Map<String, Object> createAccount(Map<String, Object> current, String email, String name, String password, String role, HttpServletRequest request) {
        requirePrivateNetwork(request);
        if (!"owner".equals(current.get("role"))) throw new ApiException(HttpStatus.FORBIDDEN, "owner role required");
        if (!List.of("owner", "operator", "analyst").contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid role");
        if (password == null || password.length() < 16 || password.length() > 256) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "管理员密码至少需要 16 位");
        String normalized = normalize(email); String displayName = name == null || name.isBlank() ? "管理员" : name.trim(); byte[] secret = security.randomBytes(20); var digest = security.passwordDigest(password);
        try {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO admin_users(id,email,display_name,password_salt,password_hash,role,totp_secret_ciphertext,totp_enabled,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, normalized, displayName, digest.salt(), digest.hash(), role, cipher.encrypt(secret), 0, security.now());
            recordAudit((String) current.get("admin_id"), "admin.account.created:" + id + ":" + role, request);
        } catch (org.springframework.dao.DuplicateKeyException error) { throw new ApiException(HttpStatus.CONFLICT, "管理员邮箱已存在"); }
        return Map.of("created", true, "email", normalized, "role", role);
    }

    private void requirePrivateNetwork(HttpServletRequest request) { if (allowedNetworks.stream().noneMatch(network -> network.contains(request.getRemoteAddr()))) throw notFound(); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "not found"); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
    private static boolean constantEquals(String expected, String actual) { return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)); }
    private void recordAudit(String adminId, String action, HttpServletRequest request) {
        jdbc.update("INSERT INTO admin_audit_logs(id,admin_user_id,action,remote_address,created_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(), adminId, action, request.getRemoteAddr(), security.now());
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            try { String[] parts = value.split("/", 2); byte[] address = InetAddress.getByName(parts[0]).getAddress(); int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8; if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException(); return new Network(address, prefix); }
            catch (Exception error) { throw new IllegalArgumentException("invalid admin CIDR: " + value, error); }
        }
        boolean contains(String candidate) {
            try { byte[] other = InetAddress.getByName(candidate).getAddress(); if (other.length != address.length) return false; int whole = prefix / 8, bits = prefix % 8; for (int i = 0; i < whole; i++) if (address[i] != other[i]) return false; return bits == 0 || ((address[whole] & (0xff << (8 - bits))) == (other[whole] & (0xff << (8 - bits)))); }
            catch (Exception error) { return false; }
        }
    }
}
