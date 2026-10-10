package com.seekwd.dormcheck.service.impl;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.CredentialCipher;
import com.seekwd.dormcheck.security.SecuritySupport;
import com.seekwd.dormcheck.service.RateLimiter;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import com.seekwd.dormcheck.mapper.SecurityMapper;
import com.seekwd.dormcheck.mapper.UserMapper;
import com.seekwd.dormcheck.mapper.NotificationMapper;
import com.seekwd.dormcheck.domain.enums.EmailCodePurpose;

@Service
public class AuthSecurityService {
    private static final java.util.Set<String> REGISTRATION_EMAIL_DOMAINS = java.util.Set.of(
        "qq.com", "foxmail.com", "163.com", "126.com", "yeah.net", "gmail.com", "outlook.com",
        "hotmail.com", "live.com", "icloud.com", "aliyun.com", "sina.com", "sohu.com", "139.com", "189.cn");
    private final SecurityMapper securityMapper;
    private final UserMapper users;
    private final NotificationMapper notifications;
    private final SecuritySupport security;
    private final CredentialCipher cipher;
    private final RateLimiter limiter;
    private final JavaMailSender mail;
    private final String mailFrom;

    public AuthSecurityService(SecurityMapper securityMapper, UserMapper users, NotificationMapper notifications, SecuritySupport security, CredentialCipher cipher,
        RateLimiter limiter, JavaMailSender mail, @Value("${dormcheck.mail-from}") String mailFrom) {
        this.securityMapper = securityMapper;
        this.users = users;
        this.notifications = notifications;
        this.security = security;
        this.cipher = cipher;
        this.limiter = limiter;
        this.mail = mail;
        this.mailFrom = mailFrom;
    }

    public void sendRegistrationCode(String email, String ip, String deviceId) {
        String normalized = normalizeEmail(email);
        requireSupportedRegistrationEmail(normalized);
        limiter.check("register-email-code", ip, normalized, 3, 3600);
        limiter.check("register-email-global", "global", normalized, 5, 3600);
        limiter.checkDevice("register-code-device", deviceId, 5, 3600);
        if (users.existsByEmail(normalized) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "email is already registered");
        }
        issueEmailCode(normalized, EmailCodePurpose.REGISTRATION.value(), ip);
    }

    public void verifyRegistrationCode(String email, String code) {
        String normalized = normalizeEmail(email);
        requireSupportedRegistrationEmail(normalized);
        verifyEmailCode(normalized, EmailCodePurpose.REGISTRATION.value(), code);
    }

    private void requireSupportedRegistrationEmail(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "" : email.substring(at + 1);
        if (!REGISTRATION_EMAIL_DOMAINS.contains(domain)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "目前仅支持常用邮箱服务商的邮箱注册");
        }
    }

    public void sendLoginCode(String email, String ip, String deviceId) {
        String normalized = normalizeEmail(email);
        limiter.check("login-email-code", ip, normalized, 5, 900);
        limiter.checkDevice("login-code-device", deviceId, 10, 900);
        issueEmailCode(normalized, EmailCodePurpose.LOGIN.value(), ip);
    }

    private void issueEmailCode(String email, String purpose, String ip) {
        limiter.check("email-code-ip", ip, purpose, 10, 3600);
        long randomValue = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(security.randomBytes(4)).getInt());
        String code = String.format(java.util.Locale.ROOT, "%06d", randomValue % 1_000_000);
        securityMapper.upsertEmailCode(email, purpose, security.hashToken(code), Instant.now().plusSeconds(600).toString());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (mailFrom != null && !mailFrom.isBlank()) message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("查寝中心邮箱验证码");
            message.setText("你的验证码是 " + code + "，10 分钟内有效。若非本人操作，请忽略此邮件。");
            mail.send(message);
        } catch (Exception error) {
            securityMapper.deleteEmailCode(email, purpose);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "验证码邮件发送失败，请稍后重试");
        }
    }

    private synchronized void verifyEmailCode(String email, String purpose, String code) {
        if (code == null || !code.matches("\\d{6}")) throw invalidCode();
        var row = securityMapper.findEmailCode(email, purpose);
        if (row == null) throw invalidCode();
        if (row.attempts >= 5 || Instant.parse(row.expiresAt).isBefore(Instant.now())) {
            securityMapper.deleteEmailCode(email, purpose);
            throw invalidCode();
        }
        securityMapper.incrementEmailCodeAttempts(email, purpose);
        if (!security.hashToken(code).equals(row.codeHash)) throw invalidCode();
        securityMapper.deleteEmailCode(email, purpose);
    }

    public Map<String, Object> verifyLogin(String email, String emailCode, String totpCode) {
        String normalized = normalizeEmail(email);
        String userId = users.findIdByEmail(normalized);
        if (userId == null) throw invalidCode();
        var mfa = securityMapper.findTotp(userId);
        if (mfa != null && mfa.enabled == 1) {
            if (!verifyTotp(cipher.decrypt(mfa.secretCiphertext), totpCode)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "二次认证码无效");
            }
        }
        verifyEmailCode(normalized, EmailCodePurpose.LOGIN.value(), emailCode);
        return Map.of("user_id", userId, "totp_enabled", mfa != null && mfa.enabled == 1);
    }

    public Map<String, String> beginTotpSetup(Map<String, Object> session) {
        String userId = (String) session.get("user_id");
        byte[] secret = security.randomBytes(20);
        securityMapper.upsertTotp(userId, cipher.encrypt(secret), security.now());
        String encoded = base32(secret);
        String label = URLEncoder.encode("查寝中心:" + session.get("email"), StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode("查寝中心", StandardCharsets.UTF_8);
        return Map.of("secret", encoded, "otpauth_url", "otpauth://totp/" + label + "?secret=" + encoded + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30");
    }

    public Map<String, Boolean> confirmTotp(Map<String, Object> session, String code) {
        String userId = (String) session.get("user_id");
        var row = securityMapper.findTotp(userId);
        if (row == null || !verifyTotp(cipher.decrypt(row.secretCiphertext), code)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "动态口令无效");
        }
        securityMapper.enableTotp(userId, security.now());
        notifications.insertEvent(java.util.UUID.randomUUID().toString(), userId, "security",
            "二次认证已启用", "验证器二次认证已启用，后续登录需要动态口令。", security.now());
        return Map.of("enabled", true);
    }

    public Map<String, Boolean> disableTotp(Map<String, Object> session, String emailCode, String totpCode) {
        String userId = (String) session.get("user_id");
        String email = users.findEmailById(userId);
        if (email == null) throw security.unauthorized();
        verifyEmailCode(email, EmailCodePurpose.LOGIN.value(), emailCode);
        var mfa = securityMapper.findEnabledTotp(userId);
        if (mfa == null || !verifyTotp(cipher.decrypt(mfa.secretCiphertext), totpCode)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "二次认证失败");
        }
        securityMapper.deleteTotp(userId);
        notifications.insertEvent(java.util.UUID.randomUUID().toString(), userId, "security",
            "二次认证已关闭", "验证器二次认证已关闭，建议保留邮箱验证码登录保护。", security.now());
        return Map.of("enabled", false);
    }

    public boolean totpEnabled(String userId) {
        return securityMapper.totpEnabled(userId) == 1;
    }

    private boolean verifyTotp(byte[] secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long counter = Instant.now().getEpochSecond() / 30;
        for (long offset = -1; offset <= 1; offset++) {
            if (totp(secret, counter + offset).equals(code)) return true;
        }
        return false;
    }

    private String totp(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private String base32(byte[] bytes) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) result.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
