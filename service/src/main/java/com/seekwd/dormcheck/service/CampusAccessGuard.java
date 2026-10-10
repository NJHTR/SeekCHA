package com.seekwd.dormcheck.service;

import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.mapper.CheckQueueMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CampusAccessGuard {
    public static final long GAP_MILLIS = 60_000;
    public static final long RISK_COOLDOWN_MILLIS = 30 * 60_000;
    private final CheckQueueMapper queue;

    public CampusAccessGuard(CheckQueueMapper queue) { this.queue = queue; }

    public Map<String, Object> call(Supplier<Map<String, Object>> operation) {
        long now = Instant.now().toEpochMilli();
        String token = UUID.randomUUID().toString();
        // A persisted lease also keeps a restarted process from bypassing the gap.
        if (queue.acquireAccess(token, now, now + 120_000) != 1) {
            throw new AccessBusyException();
        }
        Map<String, Object> result = Map.of();
        boolean threw = true;
        try {
            result = operation.get();
            threw = false;
            return result;
        } finally {
            long finished = Instant.now().toEpochMilli();
            String code = String.valueOf(result.getOrDefault("error_code", ""));
            boolean transientFailure = threw || "network".equals(code) || "server_error".equals(code) || "internal_error".equals(code);
            boolean risk = "rate_limited".equals(code) || "access_denied".equals(code) || "account_locked".equals(code);
            var state = queue.accessState();
            long cooldown = risk ? finished + RISK_COOLDOWN_MILLIS : 0;
            if (transientFailure && state.transientFailures >= 2) cooldown = Math.max(cooldown, finished + 15 * 60_000);
            queue.releaseAccess(token, finished + GAP_MILLIS, cooldown, transientFailure);
        }
    }

    public static final class AccessBusyException extends ApiException {
        public AccessBusyException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "校园平台访问正在排队或冷却中，请稍后再试。");
        }
    }
}
