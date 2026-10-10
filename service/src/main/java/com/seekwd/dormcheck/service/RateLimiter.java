package com.seekwd.dormcheck.service;
import com.seekwd.dormcheck.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.seekwd.dormcheck.mapper.RateLimitMapper;

@Service
public class RateLimiter {
    private final RateLimitMapper limits;

    public RateLimiter(RateLimitMapper limits) {
        this.limits = limits;
    }

    public void check(String action, String ip, String identity, int limit, int windowSeconds) {
        long now = Instant.now().getEpochSecond();
        String bucket = bucket(action, ip, identity);
        int attempts = limits.incrementWindow(bucket, now, now - windowSeconds);
        if (attempts > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts; try again later");
        }
        limits.deleteExpired(now - 86_400);
    }

    public void checkDevice(String action, String deviceId, int limit, int windowSeconds) {
        if (deviceId != null && deviceId.matches("(?i)[0-9a-f-]{36}")) {
            check(action, "device", deviceId, limit, windowSeconds);
        }
    }

    public void checkFailureBackoff(String action, String ip, String identity, int freeFailures,
        int initialDelaySeconds, int maxDelaySeconds, int resetAfterSeconds) {
        String bucket = bucket(action, ip, identity);
        var row = limits.find(bucket);
        if (row == null) return;
        long now = Instant.now().getEpochSecond();
        long lastFailure = row.windowStart;
        int failures = row.attempts;
        if (now - lastFailure >= resetAfterSeconds) {
            limits.delete(bucket);
            return;
        }
        if (failures <= freeFailures) return;
        int exponent = Math.min(failures - freeFailures - 1, 10);
        long delay = Math.min(maxDelaySeconds, (long) initialDelaySeconds << exponent);
        if (now < lastFailure + delay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts; wait before trying again");
        }
    }

    public void recordFailure(String action, String ip, String identity, int resetAfterSeconds) {
        long now = Instant.now().getEpochSecond();
        String bucket = bucket(action, ip, identity);
        limits.recordFailure(bucket, now, resetAfterSeconds);
    }

    public void clearFailures(String action, String ip, String identity) {
        limits.delete(bucket(action, ip, identity));
    }

    private String bucket(String action, String ip, String identity) {
        String raw = action + "|" + ip + "|" + identity.toLowerCase(java.util.Locale.ROOT);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
