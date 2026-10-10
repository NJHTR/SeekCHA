package com.seekwd.dormcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekwd.dormcheck.domain.entity.AcademicCacheEntity;
import com.seekwd.dormcheck.mapper.AcademicCacheMapper;
import com.seekwd.dormcheck.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

/**
 * Multi-level cache for non-sensitive academic responses.
 *
 * The cache is versioned by the encrypted campus credential's updated_at value,
 * so a credential change can never read data produced by the previous account.
 */
@Service
public class AcademicCacheService {
    private static final Logger log = LoggerFactory.getLogger(AcademicCacheService.class);
    private static final int MAX_LOCAL_ENTRIES = 10_000;
    private final AcademicCacheMapper mapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final long freshMillis;
    private final long staleMillis;
    private final long jitterMillis;
    private final ConcurrentMap<String, LocalEntry> local = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> refreshBackoffUntil = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor refreshExecutor = new ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
        new ThreadPoolExecutor.AbortPolicy());

    private record LocalEntry(String version, long freshUntil, long staleUntil, Map<String, Object> response) {}

    public AcademicCacheService(AcademicCacheMapper mapper, ObjectMapper objectMapper,
        ObjectProvider<StringRedisTemplate> redisProvider,
        @Value("${dormcheck.cache.redis-enabled:false}") boolean redisEnabled,
        @Value("${dormcheck.cache.l1-fresh-seconds:300}") long freshSeconds,
        @Value("${dormcheck.cache.stale-seconds:900}") long staleSeconds,
        @Value("${dormcheck.cache.jitter-seconds:60}") long jitterSeconds) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
        this.freshMillis = Math.max(60_000L, freshSeconds * 1_000L);
        this.staleMillis = Math.max(60_000L, staleSeconds * 1_000L);
        this.jitterMillis = Math.max(0L, Math.min(jitterSeconds * 1_000L, this.freshMillis / 2));
    }

    public Map<String, Object> get(String userId, String cacheType, String credentialVersion,
        Supplier<Map<String, Object>> loader) {
        return get(userId, cacheType, credentialVersion, false, loader);
    }

    public Map<String, Object> get(String userId, String cacheType, String credentialVersion,
        boolean forceRefresh, Supplier<Map<String, Object>> loader) {
        String version = credentialVersion == null ? "" : credentialVersion;
        String key = key(userId, cacheType, version);
        long now = System.currentTimeMillis();

        if (!forceRefresh) {
            LocalEntry localEntry = local.get(key);
            if (localEntry != null) {
                if (localEntry.freshUntil() > now) return localEntry.response();
                if (localEntry.staleUntil() > now) {
                    refreshAsync(key, userId, cacheType, version, loader);
                    return localEntry.response();
                }
                local.remove(key, localEntry);
            }

            AcademicCacheEntity persisted = readRedis(key);
            if (persisted == null) {
                try {
                    persisted = mapper.find(userId, cacheType, version);
                } catch (RuntimeException error) {
                    log.warn("academic cache database read failed, type={}", cacheType);
                }
            }
            if (persisted != null && persisted.staleUntil > now) {
                Map<String, Object> response = decode(persisted.payload);
                if (response != null) {
                    LocalEntry restored = new LocalEntry(version, persisted.freshUntil, persisted.staleUntil, response);
                    local.put(key, restored);
                    if (persisted.freshUntil > now) return response;
                    refreshAsync(key, userId, cacheType, version, loader);
                    return response;
                }
            }
        }
        return refreshBlocking(key, userId, cacheType, version, loader);
    }

    public void invalidateUser(String userId) {
        String prefix = userId + "|";
        local.keySet().removeIf(key -> key.startsWith(prefix));
        try {
            mapper.deleteForUser(userId);
        } catch (RuntimeException error) {
            log.warn("academic cache database invalidation failed");
        }
    }

    private Map<String, Object> refreshBlocking(String key, String userId, String cacheType,
        String version, Supplier<Map<String, Object>> loader) {
        long now = System.currentTimeMillis();
        Long retryAfter = refreshBackoffUntil.get(key);
        if (retryAfter != null) {
            if (retryAfter > now) throw new CacheRefreshBusyException();
            refreshBackoffUntil.remove(key, retryAfter);
        }
        CompletableFuture<Map<String, Object>> future = startRefresh(key, userId, cacheType, version, loader);
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }

    private void refreshAsync(String key, String userId, String cacheType, String version,
        Supplier<Map<String, Object>> loader) {
        Long retryAfter = refreshBackoffUntil.get(key);
        if (retryAfter != null && retryAfter > System.currentTimeMillis()) return;
        try {
            startRefresh(key, userId, cacheType, version, loader);
        } catch (RuntimeException error) {
            log.debug("academic cache refresh was not scheduled, type={}", cacheType);
        }
    }

    private CompletableFuture<Map<String, Object>> startRefresh(String key, String userId, String cacheType,
        String version, Supplier<Map<String, Object>> loader) {
        return inFlight.computeIfAbsent(key, ignored -> {
            CompletableFuture<Map<String, Object>> future;
            try {
                future = CompletableFuture.supplyAsync(loader, refreshExecutor);
            } catch (RuntimeException error) {
                throw error;
            }
            future.whenComplete((response, error) -> {
                try {
                    if (error == null && response != null) {
                        refreshBackoffUntil.remove(key);
                        remember(key, userId, cacheType, version, response);
                    } else if (error != null) {
                        refreshBackoffUntil.put(key, System.currentTimeMillis() + 30_000L);
                        log.warn("academic cache refresh failed, type={}: {}", cacheType, safeMessage(error));
                    }
                } finally {
                    inFlight.remove(key, future);
                }
            });
            return future;
        });
    }

    private void remember(String key, String userId, String cacheType, String version, Map<String, Object> response) {
        long now = System.currentTimeMillis();
        long jitter = jitterMillis == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitterMillis, jitterMillis + 1);
        long freshUntil = now + Math.max(60_000L, freshMillis + jitter);
        long staleUntil = freshUntil + staleMillis;
        try {
            AcademicCacheEntity row = new AcademicCacheEntity();
            row.userId = userId;
            row.cacheType = cacheType;
            row.credentialVersion = version;
            row.payload = objectMapper.writeValueAsString(response);
            if (row.payload.length() > 1_000_000) {
                log.warn("academic cache payload too large, type={}", cacheType);
                local.put(key, new LocalEntry(version, freshUntil, staleUntil, response));
                return;
            }
            row.fetchedAt = now;
            row.freshUntil = freshUntil;
            row.staleUntil = staleUntil;
            row.updatedAt = java.time.Instant.now().toString();
            mapper.upsert(row);
            writeRedis(key, row);
        } catch (Exception error) {
            log.warn("academic cache persistence failed, type={}", cacheType);
        }
        if (local.size() >= MAX_LOCAL_ENTRIES) {
            long pruneNow = System.currentTimeMillis();
            local.entrySet().removeIf(entry -> entry.getValue().staleUntil() <= pruneNow);
            if (local.size() >= MAX_LOCAL_ENTRIES) {
                local.keySet().stream().limit(128).toList().forEach(local::remove);
            }
        }
        local.put(key, new LocalEntry(version, freshUntil, staleUntil, response));
    }

    private AcademicCacheEntity readRedis(String key) {
        if (!redisEnabled) return null;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return null;
            String value = redis.opsForValue().get(redisKey(key));
            return value == null ? null : objectMapper.readValue(value, AcademicCacheEntity.class);
        } catch (RuntimeException | java.io.IOException error) {
            log.debug("academic redis read unavailable");
            return null;
        }
    }

    private void writeRedis(String key, AcademicCacheEntity row) {
        if (!redisEnabled) return;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return;
            long ttl = Math.max(1_000L, row.staleUntil - System.currentTimeMillis());
            redis.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(row), Duration.ofMillis(ttl));
        } catch (RuntimeException | java.io.IOException error) {
            log.debug("academic redis write unavailable");
        }
    }

    private Map<String, Object> decode(String payload) {
        try {
            return objectMapper.readValue(payload, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, Object.class));
        } catch (Exception error) {
            return null;
        }
    }

    private String key(String userId, String cacheType, String version) {
        return userId + "|" + cacheType + "|" + version;
    }

    private String redisKey(String key) {
        return "dormcheck:academic:" + sha256(key);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** Prevents an expired entry from turning an upstream failure into a retry storm. */
    public static final class CacheRefreshBusyException extends ApiException {
        public CacheRefreshBusyException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "校园平台刷新正在冷却中，请稍后再试。");
        }
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }
}
