package com.seekwd.dormcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekwd.dormcheck.domain.entity.AcademicCacheEntity;
import com.seekwd.dormcheck.mapper.AcademicCacheMapper;
import com.seekwd.dormcheck.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final ConcurrentMap<String, Long> userGenerations = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor refreshExecutor = new ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
        new ThreadPoolExecutor.AbortPolicy());

    private record LocalEntry(String version, long freshUntil, long staleUntil, Map<String, Object> response) {}

    public AcademicCacheService(AcademicCacheMapper mapper, ObjectMapper objectMapper,
        ObjectProvider<StringRedisTemplate> redisProvider,
        @Value("${dormcheck.cache.redis-enabled:false}") boolean redisEnabled,
        @Value("${dormcheck.cache.l1-fresh-seconds:300}") long freshSeconds,
        @Value("${dormcheck.cache.stale-seconds:2592000}") long staleSeconds,
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
        return get(userId, cacheType, credentialVersion, "", false, loader);
    }

    public Map<String, Object> get(String userId, String cacheType, String credentialVersion,
        boolean forceRefresh, Supplier<Map<String, Object>> loader) {
        return get(userId, cacheType, credentialVersion, "", forceRefresh, loader);
    }

    public Map<String, Object> get(String userId, String cacheType, String credentialVersion,
        String cacheScope, boolean forceRefresh, Supplier<Map<String, Object>> loader) {
        String version = credentialVersion == null ? "" : credentialVersion;
        String scope = cacheScope == null ? "" : cacheScope;
        String key = key(userId, cacheType, version, scope);
        long now = System.currentTimeMillis();
        LocalEntry fallback = null;

        LocalEntry localEntry = local.get(key);
        if (localEntry != null) {
            if (localEntry.staleUntil() > now) fallback = localEntry;
            if (!forceRefresh) {
                if (localEntry.freshUntil() > now) return withCacheStatus(localEntry.response(), "fresh");
                if (localEntry.staleUntil() > now) {
                    refreshAsync(key, userId, cacheType, version, loader);
                    return withCacheStatus(localEntry.response(), "stale");
                }
                local.remove(key, localEntry);
            }
        }

        AcademicCacheEntity persisted = readRedis(userId, cacheType, version, scope);
        if (persisted == null) {
            try {
                persisted = mapper.find(userId, cacheType, version);
                if (persisted != null && scopeMatches(persisted, scope)) writeRedis(userId, cacheType, persisted);
            } catch (RuntimeException error) {
                log.warn("academic cache database read failed, type={}", cacheType);
            }
        }
        if (persisted != null && scopeMatches(persisted, scope)) {
            Map<String, Object> response = decode(persisted.payload);
            if (response != null) {
                LocalEntry restored = new LocalEntry(version, persisted.freshUntil, persisted.staleUntil, response);
                if (restored.staleUntil() > now && (fallback == null || fallback.staleUntil() < restored.staleUntil())) {
                    fallback = restored;
                }
                if (!forceRefresh && restored.staleUntil() > now) {
                    local.put(key, restored);
                    if (restored.freshUntil() > now) return withCacheStatus(response, "fresh");
                    refreshAsync(key, userId, cacheType, version, loader);
                    return withCacheStatus(response, "stale");
                }
            }
        }
        try {
            Map<String, Object> refreshed = refreshBlocking(key, userId, cacheType, version, loader);
            if (hasUsableData(cacheType, refreshed)) return withCacheStatus(refreshed, "refreshed");
            if (fallback != null) return withCacheStatus(fallback.response(), "stale-fallback");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "教务数据不完整，请稍后重试。");
        } catch (RuntimeException error) {
            if (fallback == null) throw error;
            log.warn("academic cache served persisted data after refresh failure, type={}", cacheType);
            return withCacheStatus(fallback.response(), "stale-fallback");
        }
    }

    public void invalidateUser(String userId) {
        userGenerations.merge(userId, 1L, Long::sum);
        String prefix = userId + "|";
        local.keySet().removeIf(key -> key.startsWith(prefix));
        try {
            mapper.deleteForUser(userId);
        } catch (RuntimeException error) {
            log.warn("academic cache database invalidation failed");
        }
        deleteRedis(userId, "notices");
        deleteRedis(userId, "schedule");
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

    private synchronized CompletableFuture<Map<String, Object>> startRefresh(String key, String userId, String cacheType,
        String version, Supplier<Map<String, Object>> loader) {
        CompletableFuture<Map<String, Object>> current = inFlight.get(key);
        if (current != null && !current.isDone()) return current;
        if (current != null) inFlight.remove(key, current);

        long generation = userGenerations.getOrDefault(userId, 0L);
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        inFlight.put(key, result);
        try {
            CompletableFuture.supplyAsync(loader, refreshExecutor).whenComplete((response, error) -> {
                Throwable completionError = error;
                try {
                    if (error == null && response != null && hasUsableData(cacheType, response)
                        && userGenerations.getOrDefault(userId, 0L) == generation) {
                        refreshBackoffUntil.remove(key);
                        remember(key, userId, cacheType, version, response, generation);
                    } else if (error == null) {
                        refreshBackoffUntil.put(key, System.currentTimeMillis() + 30_000L);
                        log.warn("academic cache refresh returned no usable data, type={}", cacheType);
                    } else {
                        refreshBackoffUntil.put(key, System.currentTimeMillis() + 30_000L);
                        log.warn("academic cache refresh failed, type={}: {}", cacheType, safeMessage(error));
                    }
                } catch (Throwable cacheError) {
                    completionError = cacheError;
                } finally {
                    inFlight.remove(key, result);
                }
                if (completionError == null) result.complete(response);
                else result.completeExceptionally(completionError);
            });
        } catch (RuntimeException error) {
            inFlight.remove(key, result);
            result.completeExceptionally(error);
        }
        return result;
    }

    private boolean hasUsableData(String cacheType, Map<String, Object> response) {
        if (response == null) return false;
        if (!"notices".equals(cacheType) && !"schedule".equals(cacheType)) return true;
        Object items = response.get("items");
        if (!(items instanceof Collection<?> collection)) return false;
        if (!Boolean.TRUE.equals(response.get("data_complete"))) return false;
        if (collection.isEmpty()) {
            return !"schedule".equals(cacheType)
                || !String.valueOf(response.getOrDefault("academic_scope", "")).isBlank();
        }
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> row)) return false;
            if ("notices".equals(cacheType)) {
                if (!nonBlank(row, "title") || !nonBlank(row, "date") || !nonBlank(row, "url")) return false;
            } else {
                if (!nonBlank(row, "course") || !validWeekday(row.get("weekday")) || !validPeriods(row.get("periods"))) return false;
            }
        }
        return "notices".equals(cacheType)
            || !String.valueOf(response.getOrDefault("academic_scope", "")).isBlank();
    }

    private boolean nonBlank(Map<?, ?> row, String field) {
        Object value = row.get(field);
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean validWeekday(Object value) {
        if (value == null) return false;
        String weekday = String.valueOf(value);
        return weekday.matches("[1-7]") || weekday.matches("星期[一二三四五六日天]")
            || weekday.matches("周[一二三四五六日天]");
    }

    private boolean validPeriods(Object value) {
        if (value == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(String.valueOf(value));
        if (!matcher.find()) return false;
        int start = Integer.parseInt(matcher.group());
        if (!matcher.find()) return start >= 1 && start <= 11;
        int end = Integer.parseInt(matcher.group());
        return start >= 1 && end >= start && end <= 11;
    }

    private void remember(String key, String userId, String cacheType, String version, Map<String, Object> response,
        long generation) {
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
                if (userGenerations.getOrDefault(userId, 0L) == generation) {
                    local.put(key, new LocalEntry(version, freshUntil, staleUntil, response));
                }
                return;
            }
            row.fetchedAt = now;
            row.freshUntil = freshUntil;
            row.staleUntil = staleUntil;
            row.updatedAt = java.time.Instant.now().toString();
            int accepted = mapper.upsert(row);
            if (accepted == 0 || userGenerations.getOrDefault(userId, 0L) != generation) return;
            writeRedis(userId, cacheType, row);
        } catch (Exception error) {
            log.warn("academic cache persistence failed, type={}", cacheType);
        }
        if (userGenerations.getOrDefault(userId, 0L) != generation) return;
        if (local.size() >= MAX_LOCAL_ENTRIES) {
            long pruneNow = System.currentTimeMillis();
            local.entrySet().removeIf(entry -> entry.getValue().staleUntil() <= pruneNow);
            if (local.size() >= MAX_LOCAL_ENTRIES) {
                local.keySet().stream().limit(128).toList().forEach(local::remove);
            }
        }
        local.put(key, new LocalEntry(version, freshUntil, staleUntil, response));
    }

    private AcademicCacheEntity readRedis(String userId, String cacheType, String version, String scope) {
        if (!redisEnabled) return null;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return null;
            String value = redis.opsForValue().get(redisKey(userId, cacheType));
            if (value == null) return null;
            AcademicCacheEntity row = objectMapper.readValue(value, AcademicCacheEntity.class);
            return matches(row, userId, cacheType, version) && scopeMatches(row, scope) ? row : null;
        } catch (RuntimeException | java.io.IOException error) {
            log.debug("academic redis read unavailable");
            return null;
        }
    }

    private boolean matches(AcademicCacheEntity row, String userId, String cacheType, String version) {
        return row != null && userId.equals(row.userId) && cacheType.equals(row.cacheType)
            && version.equals(row.credentialVersion);
    }

    private boolean scopeMatches(AcademicCacheEntity row, String expectedScope) {
        if (expectedScope == null || expectedScope.isBlank()) return true;
        Map<String, Object> payload = decode(row.payload);
        return payload != null && expectedScope.equals(String.valueOf(payload.getOrDefault("academic_scope", "")));
    }

    private Map<String, Object> withCacheStatus(Map<String, Object> response, String status) {
        if (response == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>(response);
        copy.put("cache_status", status);
        return copy;
    }

    private void writeRedis(String userId, String cacheType, AcademicCacheEntity row) {
        if (!redisEnabled) return;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return;
            long ttl = Math.max(1_000L, row.staleUntil - System.currentTimeMillis());
            redis.opsForValue().set(redisKey(userId, cacheType), objectMapper.writeValueAsString(row), Duration.ofMillis(ttl));
        } catch (RuntimeException | java.io.IOException error) {
            log.debug("academic redis write unavailable");
        }
    }

    private void deleteRedis(String userId, String cacheType) {
        if (!redisEnabled) return;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) redis.delete(redisKey(userId, cacheType));
        } catch (RuntimeException error) {
            log.debug("academic redis invalidation unavailable");
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

    private String key(String userId, String cacheType, String version, String scope) {
        return userId + "|" + cacheType + "|" + version + "|" + scope;
    }

    private String redisKey(String userId, String cacheType) {
        return "dormcheck:academic:" + sha256(userId + "|" + cacheType);
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
