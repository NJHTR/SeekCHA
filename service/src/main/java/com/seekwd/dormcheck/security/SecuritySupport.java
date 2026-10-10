package com.seekwd.dormcheck.security;
import com.seekwd.dormcheck.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SecuritySupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HASH_ITERATIONS = 600_000;
    private final byte[] dummySalt = randomBytes(16);
    private final byte[] dummyHash = derive("dorm-check-invalid-password".toCharArray(), dummySalt);

    public byte[] randomBytes(int count) {
        byte[] value = new byte[count];
        RANDOM.nextBytes(value);
        return value;
    }

    public String randomToken(int count) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(count));
    }

    public String hashToken(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public PasswordDigest passwordDigest(String password) {
        byte[] salt = randomBytes(16);
        return new PasswordDigest(salt, derive(password.toCharArray(), salt));
    }

    public boolean verifyPassword(String password, byte[] salt, byte[] expected) {
        byte[] actual = derive(password.toCharArray(), salt == null ? dummySalt : salt);
        return MessageDigest.isEqual(actual, expected == null ? dummyHash : expected) && expected != null;
    }

    private byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, HASH_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("password hashing is unavailable", error);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    public String now() {
        return Instant.now().toString();
    }

    public String sessionExpiry(int days) {
        return Instant.now().plusSeconds(days * 86_400L).toString();
    }

    public record PasswordDigest(byte[] salt, byte[] hash) {}

    public ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
