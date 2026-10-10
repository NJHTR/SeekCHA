package com.seekwd.dormcheck.service.impl;
import com.seekwd.dormcheck.api.dto.AuthRequest;
import com.seekwd.dormcheck.api.dto.CredentialRequest;
import com.seekwd.dormcheck.api.dto.EmailCodeRequest;
import com.seekwd.dormcheck.api.dto.LoginVerification;
import com.seekwd.dormcheck.api.dto.ScheduleRequest;
import com.seekwd.dormcheck.domain.dto.CampusCredentials;
import com.seekwd.dormcheck.engine.CheckEngine;
import com.seekwd.dormcheck.api.dto.AuthRequest;
import com.seekwd.dormcheck.api.dto.CredentialRequest;
import com.seekwd.dormcheck.api.dto.EmailCodeRequest;
import com.seekwd.dormcheck.api.dto.LoginVerification;
import com.seekwd.dormcheck.api.dto.ScheduleRequest;
import com.seekwd.dormcheck.domain.dto.CampusCredentials;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.scheduler.CheckScheduler;
import com.seekwd.dormcheck.security.CredentialCipher;
import com.seekwd.dormcheck.security.SecuritySupport;
import com.seekwd.dormcheck.service.DormCheckService;
import com.seekwd.dormcheck.service.RateLimiter;
import com.seekwd.dormcheck.service.SessionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.seekwd.dormcheck.service.CheckQueueService;
import com.seekwd.dormcheck.service.CampusAccessGuard;
import com.seekwd.dormcheck.service.AcademicCacheService;
import com.seekwd.dormcheck.mapper.CheckQueueMapper;
import com.seekwd.dormcheck.domain.entity.CredentialEntity;
import com.seekwd.dormcheck.domain.entity.RunEntity;
import com.seekwd.dormcheck.domain.entity.ScheduleEntity;
import com.seekwd.dormcheck.domain.entity.UserEntity;
import com.seekwd.dormcheck.mapper.CredentialMapper;
import com.seekwd.dormcheck.mapper.DeviceMapper;
import com.seekwd.dormcheck.mapper.NotificationMapper;
import com.seekwd.dormcheck.mapper.RunMapper;
import com.seekwd.dormcheck.mapper.ScheduleMapper;
import com.seekwd.dormcheck.mapper.SessionMapper;
import com.seekwd.dormcheck.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DormCheckServiceImpl implements DormCheckService {
    private static final Logger log = LoggerFactory.getLogger(DormCheckServiceImpl.class);
    private static final int CHECKS_PAGE_SIZE = 20;
    private final UserMapper users;
    private final ScheduleMapper schedules;
    private final CredentialMapper credentialsMapper;
    private final DeviceMapper devicesMapper;
    private final SessionMapper sessionMapper;
    private final RunMapper runsMapper;
    private final NotificationMapper notificationsMapper;
    private final TransactionTemplate transactionTemplate;
    private final SecuritySupport security;
    private final CredentialCipher cipher;
    private final SessionService sessions;
    private final RateLimiter limiter;
    private final CheckEngine engine;
    private final ObjectMapper mapper;
    private final AuthSecurityService authSecurity;
    private final JavaMailSender mail;
    private final String mailFrom;
    private final AcademicCacheService academicCache;
    private final ObjectProvider<CheckScheduler> scheduler;
    private final ObjectProvider<CheckQueueService> checkQueue;
    private final CampusAccessGuard campusAccess;
    private final CheckQueueMapper queueMapper;

    public DormCheckServiceImpl(UserMapper users, ScheduleMapper schedules, CredentialMapper credentialsMapper,
        DeviceMapper devicesMapper, SessionMapper sessionMapper, RunMapper runsMapper,
        NotificationMapper notificationsMapper, PlatformTransactionManager transactionManager,
        SecuritySupport security, CredentialCipher cipher,
        SessionService sessions, RateLimiter limiter, CheckEngine engine, ObjectMapper mapper,
        ObjectProvider<CheckScheduler> scheduler, AuthSecurityService authSecurity,
        JavaMailSender mail, @Value("${dormcheck.mail-from}") String mailFrom,
        ObjectProvider<CheckQueueService> checkQueue, CampusAccessGuard campusAccess, CheckQueueMapper queueMapper,
        AcademicCacheService academicCache) {
        this.users = users;
        this.schedules = schedules;
        this.credentialsMapper = credentialsMapper;
        this.devicesMapper = devicesMapper;
        this.sessionMapper = sessionMapper;
        this.runsMapper = runsMapper;
        this.notificationsMapper = notificationsMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.security = security;
        this.cipher = cipher;
        this.sessions = sessions;
        this.limiter = limiter;
        this.engine = engine;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.authSecurity = authSecurity;
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.checkQueue = checkQueue;
        this.campusAccess = campusAccess;
        this.queueMapper = queueMapper;
        this.academicCache = academicCache;
    }

    @Transactional
    public Map<String, Object> register(AuthRequest payload, HttpServletRequest request, HttpServletResponse response) {
        String email = normalizeEmail(payload.email());
        String name = payload.name() == null ? "" : payload.name().trim();
        if (name.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "name is required");
        limiter.check("register-ip", request.getRemoteAddr(), "", 10, 3600);
        limiter.check("register-email", request.getRemoteAddr(), email, 3, 3600);
        limiter.checkDevice("register-device", request.getHeader("X-Device-ID"), 8, 3600);
        authSecurity.verifyRegistrationCode(email, payload.emailCode());
        var digest = security.passwordDigest(payload.password());
        String userId = UUID.randomUUID().toString();
        try {
            UserEntity user = new UserEntity();
            user.id = userId; user.email = email; user.displayName = name;
            user.passwordSalt = digest.salt(); user.passwordHash = digest.hash();
            user.createdAt = security.now();
            users.insert(user);
            schedules.insertDefaults(userId, security.now());
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "email is already registered");
        }
        String csrf = sessions.issue(response, userId, request);
        return authResponse(userId, email, name, csrf, sessions.currentDeviceId(request, userId));
    }

    public void sendRegistrationCode(EmailCodeRequest payload, HttpServletRequest request) {
        authSecurity.sendRegistrationCode(payload.email(), request.getRemoteAddr(), request.getHeader("X-Device-ID"));
    }

    public Map<String, Object> login(AuthRequest payload, HttpServletRequest request) {
        String email = normalizeEmail(payload.email());
        limiter.check("login-ip", request.getRemoteAddr(), "", 30, 900);
        limiter.check("login-email", request.getRemoteAddr(), email, 6, 900);
        limiter.check("login-email-global", "global", email, 20, 3600);
        limiter.checkDevice("login-device", request.getHeader("X-Device-ID"), 15, 900);
        limiter.checkFailureBackoff("login-failure", request.getRemoteAddr(), email, 3, 5, 300, 86_400);
        UserEntity row = users.findAuthenticationByEmail(email);
        byte[] salt = row == null ? null : row.passwordSalt;
        byte[] hash = row == null ? null : row.passwordHash;
        if (!security.verifyPassword(payload.password(), salt, hash)) {
            limiter.recordFailure("login-failure", request.getRemoteAddr(), email, 86_400);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "email or password is incorrect");
        }
        limiter.clearFailures("login-failure", request.getRemoteAddr(), email);
        authSecurity.sendLoginCode(email, request.getRemoteAddr(), request.getHeader("X-Device-ID"));
        return Map.of("verification_required", true, "email", email, "totp_required", authSecurity.totpEnabled(row.id));
    }

    public Map<String, Object> verifyLogin(LoginVerification payload, HttpServletRequest request, HttpServletResponse response) {
        limiter.check("login-verify-ip", request.getRemoteAddr(), "", 12, 900);
        limiter.check("login-verify-email", request.getRemoteAddr(), normalizeEmail(payload.email()), 8, 900);
        limiter.check("login-verify-email-global", "global", normalizeEmail(payload.email()), 20, 3600);
        limiter.checkDevice("login-verify-device", request.getHeader("X-Device-ID"), 12, 900);
        Map<String, Object> verified = authSecurity.verifyLogin(payload.email(), payload.emailCode(), payload.totpCode());
        String userId = (String) verified.get("user_id");
        UserEntity row = users.findProfileById(userId);
        if (row == null) throw security.unauthorized();
        String csrf = sessions.issue(response, userId, request);
        String email = row.email;
        String name = row.displayName;
        return authResponse(userId, email, name, csrf, sessions.currentDeviceId(request, userId));
    }

    public Map<String, Object> session(HttpServletRequest request) {
        return sessions.find(request).map(row -> Map.<String, Object>of(
            "authenticated", true,
            "csrf_token", row.get("csrf_token"),
            "current_device_id", row.get("current_device_id") == null ? "" : row.get("current_device_id"),
            "user", user(row)))
            .orElseGet(() -> Map.of("authenticated", false));
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(request, response);
    }

    public List<Map<String, Object>> devices(Map<String, Object> session) {
        return devicesMapper.findByUser(userId(session)).stream().map(device -> Map.<String, Object>of(
            "id", device.id, "name", device.deviceName, "platform", device.platform,
            "language", device.language, "timezone", device.timezone, "screen_size", device.screenSize,
            "hardware_threads", device.hardwareThreads == null ? 0 : device.hardwareThreads,
            "device_memory", device.deviceMemory == null ? 0 : device.deviceMemory,
            "created_at", device.createdAt, "last_seen_at", device.lastSeenAt)).toList();
    }

    public boolean revokeDevice(Map<String, Object> session, String deviceId) {
        String userId = userId(session);
        if (devicesMapper.countOwned(deviceId, userId) == 0) throw security.unauthorized();
        sessionMapper.deleteForDevice(deviceId);
        devicesMapper.deleteOwned(deviceId, userId);
        addNotification(userId, "security", "登录设备已撤销", "一个登录设备已被撤销，相关会话已失效。", security.now());
        return true;
    }

    public Map<String, Object> dashboard(Map<String, Object> session) {
        String userId = userId(session);
        ScheduleEntity scheduleRow = schedules.findByUserId(userId);
        Map<String, Object> schedule = scheduleRow == null
            ? Map.of("enabled", false, "run_time", "21:40", "days_of_week", List.of(1, 2, 3, 4, 5, 6, 7), "timezone", "Asia/Shanghai")
            : Map.of("enabled", scheduleRow.enabled != 0,
                "run_time", scheduleRow.runTime,
                "days_of_week", parseDaysOfWeek(scheduleRow.daysOfWeek),
                "timezone", scheduleRow.timezone);
        CredentialEntity credentialRow = credentialsMapper.findSummary(userId);
        Map<String, Object> credential = credentialRow == null
            ? Map.of("configured", false, "account_hint", "", "verified", false, "updated_at", "")
            : Map.of("configured", true, "account_hint", credentialRow.accountHint,
                "verified", credentialRow.verifiedAt != null,
                "updated_at", credentialRow.updatedAt);
        List<RunEntity> storedRuns = runsMapper.findRecent(userId, 4);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (RunEntity row : storedRuns) {
            runs.add(Map.of("id", row.id, "status", row.status, "message", row.message,
                "time", row.startedAt, "duration", String.format(java.util.Locale.ROOT, "%.1f 秒", row.durationMs / 1000.0)));
        }
        Instant recentCutoff = Instant.now().minusSeconds(7 * 24 * 60 * 60L);
        var allTimeStats = runsMapper.aggregateAll(userId);
        var recentStats = runsMapper.aggregateSince(userId, recentCutoff.toString());
        long total = allTimeStats.total;
        long successful = allTimeStats.successCount;
        long recentTotal = recentStats.total;
        long recentSuccessful = recentStats.successCount;
        Map<String, Object> stats = Map.of("total", total, "success_count", successful,
            "recent_total", recentTotal, "recent_success_count", recentSuccessful,
            "success_rate", recentTotal == 0 ? 0 : Math.round(recentSuccessful * 100.0 / recentTotal));
        return Map.of("schedule", schedule, "credential", credential, "runs", runs, "stats", stats);
    }

    public Map<String, Object> checks(Map<String, Object> session, int requestedPage, String status) {
        String userId = userId(session);
        if (requestedPage < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "page must be positive");
        if (!List.of("all", "success", "failure").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid status filter");
        }

        long total = runsMapper.countForHistory(userId, status);
        int totalPages = (int) Math.ceil(total / (double) CHECKS_PAGE_SIZE);
        int page = totalPages == 0 ? 1 : Math.min(requestedPage, totalPages);
        long offset = (long) (page - 1) * CHECKS_PAGE_SIZE;
        List<RunEntity> rows = runsMapper.findHistory(userId, status, CHECKS_PAGE_SIZE, offset);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RunEntity row : rows) {
            items.add(Map.of("id", row.id, "status", row.status, "message", row.message,
                "time", row.startedAt, "duration", String.format(java.util.Locale.ROOT, "%.1f 秒", row.durationMs / 1000.0)));
        }
        return Map.of("items", items, "total", total, "page", page, "page_size", CHECKS_PAGE_SIZE,
            "total_pages", totalPages, "status", status);
    }

    public Map<String, Object> notifications(Map<String, Object> session) {
        String userId = userId(session);
        var rows = notificationsMapper.findLatest(userId, 20);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows) {
            items.add(Map.of("id", row.id, "type", row.type == null ? "run" : row.type,
                "title", row.title == null || row.title.isBlank() ? "查寝通知" : row.title,
                "status", row.status == null ? "success" : row.status, "message", row.message,
                "time", row.time, "duration", String.format(java.util.Locale.ROOT, "%.1f 秒", row.durationMs / 1000.0),
                "read", row.readAt != null));
        }
        return Map.of("items", items, "unread_count", notificationsMapper.countUnread(userId));
    }

    public Map<String, Object> academicNotices(Map<String, Object> session, HttpServletRequest request) {
        return academicNotices(session, request, false);
    }

    public Map<String, Object> academicNotices(Map<String, Object> session, HttpServletRequest request, boolean forceRefresh) {
        String userId = userId(session);
        CredentialEntity summary = credentialsMapper.findSummary(userId);
        if (summary == null) {
            throw new ApiException(HttpStatus.CONFLICT, "请先在账号设置中配置校园平台账号。");
        }
        String credentialVersion = summary.updatedAt == null ? "" : summary.updatedAt;
        if (forceRefresh) limiter.check("academic-notices-refresh", request.getRemoteAddr(), userId, 3, 600);
        return academicCache.get(userId, "notices", credentialVersion, forceRefresh, () -> {
            limiter.check("academic-notices", request.getRemoteAddr(), userId, 12, 3600);
            CampusCredentials credentials = loadCredentials(userId);
            Map<String, Object> result = campusAccess.call(() -> engine.notices(credentials.account(), credentials.password()));
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String message = String.valueOf(result.getOrDefault("message", "教务公告获取失败，请稍后重试。"));
                throw new ApiException(HttpStatus.BAD_GATEWAY, message);
            }
            Object items = result.get("items");
            if (!(items instanceof List<?>)) items = List.of();
            return Map.of("items", items, "fetched_at", security.now());
        });
    }

    public Map<String, Object> academicSchedule(Map<String, Object> session, HttpServletRequest request) {
        return academicSchedule(session, request, false);
    }

    public Map<String, Object> academicSchedule(Map<String, Object> session, HttpServletRequest request, boolean forceRefresh) {
        String userId = userId(session);
        CredentialEntity summary = credentialsMapper.findSummary(userId);
        if (summary == null) {
            throw new ApiException(HttpStatus.CONFLICT, "请先在账号设置中配置校园平台账号。");
        }
        String credentialVersion = summary.updatedAt == null ? "" : summary.updatedAt;
        if (forceRefresh) limiter.check("academic-schedule-refresh", request.getRemoteAddr(), userId, 3, 600);
        return academicCache.get(userId, "schedule", credentialVersion, forceRefresh, () -> {
            limiter.check("academic-schedule-v2", request.getRemoteAddr(), userId, 12, 3600);
            CampusCredentials credentials = loadCredentials(userId);
            Map<String, Object> result = campusAccess.call(() -> engine.academicSchedule(credentials.account(), credentials.password()));
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String message = String.valueOf(result.getOrDefault("message", "我的课表获取失败，请稍后重试。"));
                throw new ApiException(HttpStatus.BAD_GATEWAY, message);
            }
            Object items = result.get("items");
            if (!(items instanceof List<?>)) items = List.of();
            return Map.of("items", items, "fetched_at", security.now());
        });
    }

    public Map<String, Object> markNotificationsRead(Map<String, Object> session) {
        int updated = notificationsMapper.markAllRead(userId(session), security.now());
        return Map.of("updated", updated);
    }

    public Map<String, Object> markNotificationRead(Map<String, Object> session, String notificationId) {
        int updated = notificationsMapper.markOneRead(notificationId, userId(session), security.now());
        return Map.of("updated", updated > 0);
    }

    public Map<String, Object> saveCredentials(CredentialRequest payload, Map<String, Object> session) {
        String account = payload.account().trim();
        byte[] encrypted;
        try {
            encrypted = cipher.encrypt(mapper.writeValueAsBytes(new CampusCredentials(account, payload.password())));
        } catch (Exception error) {
            throw new IllegalStateException("credential encryption failed", error);
        }
        String hint = "*".repeat(Math.max(4, account.length() - 2)) + account.substring(Math.max(0, account.length() - 2));
        CredentialEntity entity = new CredentialEntity();
        entity.userId = userId(session);
        entity.ciphertext = encrypted;
        entity.accountHint = hint;
        entity.updatedAt = security.now();
        credentialsMapper.upsert(entity);
        academicCache.invalidateUser(userId(session));
        addNotification(userId(session), "credentials", "校园账号已更新", "校园平台账号凭据已安全保存，请重新验证后再启用定时任务。", security.now());
        return Map.of("configured", true, "account_hint", hint);
    }

    public Map<String, Object> verifyCredentials(Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("credential-verify", request.getRemoteAddr(), userId, 5, 3600);
        CampusCredentials credentials = loadCredentials(userId);
        Map<String, Object> result = campusAccess.call(() -> engine.verify(credentials.account(), credentials.password()));
        boolean verified = Boolean.TRUE.equals(result.get("verified"));
        credentialsMapper.updateVerification(userId, verified ? security.now() : null, security.now());
        addNotification(userId, "credentials", verified ? "校园账号验证成功" : "校园账号验证失败",
            String.valueOf(result.getOrDefault("message", verified ? "校园平台登录验证成功。" : "校园平台登录验证失败，请稍后重试。")), security.now());
        return Map.of("verified", verified, "message", String.valueOf(result.getOrDefault("message", "校园平台登录验证失败，请稍后重试。")));
    }

    public Map<String, Object> deleteCredentials(Map<String, Object> session) {
        String userId = userId(session);
        credentialsMapper.delete(userId);
        checkQueue.getObject().cancelScheduled(userId);
        academicCache.invalidateUser(userId);
        schedules.disable(userId, security.now());
        scheduler.getObject().cancel(userId);
        addNotification(userId, "credentials", "校园账号已删除", "已删除托管的校园平台账号，定时查寝已暂停。", security.now());
        return Map.of("configured", false);
    }

    public Map<String, Object> saveSchedule(ScheduleRequest payload, Map<String, Object> session) {
        if (payload.daysOfWeek() == null || payload.daysOfWeek().isEmpty() || payload.daysOfWeek().stream().anyMatch(day -> day == null || day < 1 || day > 7) ||
            payload.daysOfWeek().stream().distinct().count() != payload.daysOfWeek().size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "select one or more weekdays");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(payload.timezone());
        } catch (Exception error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported timezone");
        }
        String userId = userId(session);
        if (payload.enabled()) {
            CredentialEntity credential = credentialsMapper.findSummary(userId);
            if (credential == null) throw new ApiException(HttpStatus.CONFLICT, "campus account is not configured");
            if (credential.verifiedAt == null) throw new ApiException(HttpStatus.CONFLICT, "campus account must be verified before scheduling");
        }
        List<Integer> days = payload.daysOfWeek().stream().sorted().toList();
        String storedDays = days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.userId = userId;
        schedule.enabled = payload.enabled() ? 1 : 0;
        schedule.runTime = payload.runTime();
        schedule.daysOfWeek = storedDays;
        schedule.timezone = zone.getId();
        schedule.updatedAt = security.now();
        schedules.upsert(schedule);
        if (payload.enabled()) scheduler.getObject().schedule(userId, payload.runTime(), days, zone.getId());
        else {
            scheduler.getObject().cancel(userId);
            checkQueue.getObject().cancelScheduled(userId);
        }
        String scheduleMessage = payload.enabled()
            ? "定时查寝已启用：" + payload.runTime() + "，" + (days.size() == 7 ? "每天执行" : "每周 " + storedDays)
            : "定时查寝已暂停。";
        addNotification(userId, "schedule", payload.enabled() ? "定时任务已启用" : "定时任务已暂停", scheduleMessage, security.now());
        return Map.of("enabled", payload.enabled(), "run_time", payload.runTime(), "days_of_week", days, "timezone", zone.getId());
    }

    private List<Integer> parseDaysOfWeek(String value) {
        return java.util.Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }

    public Map<String, Object> runNow(Map<String, Object> session, HttpServletRequest request) {
        limiter.check("manual-check-ip", request.getRemoteAddr(), "", 20, 3600);
        limiter.check("manual-check-user", request.getRemoteAddr(), userId(session), 6, 3600);
        return checkQueue.getObject().enqueue(userId(session), "manual");
    }

    public void runScheduled(String userId) {
        try {
            checkQueue.getObject().enqueue(userId, "scheduled");
        } catch (RuntimeException error) {
            log.warn("could not enqueue scheduled dorm check for user {}: {}", userId, safeErrorMessage(error));
        }
    }

    public Map<String, Object> checkQueueStatus(Map<String, Object> session) {
        return checkQueue.getObject().status(userId(session));
    }

    public Map<String, Object> runQueuedAttempt(String userId) {
        CampusCredentials credentials = loadCredentials(userId);
        return engine.check(credentials.account(), credentials.password());
    }

    public void finishQueuedCheck(com.seekwd.dormcheck.domain.entity.CheckJobEntity job, String status, String message, int duration) {
        RunEntity run = new RunEntity();
        run.id = job.id; run.userId = job.userId; run.status = status; run.message = message;
        run.startedAt = job.startedAt == null ? security.now() : job.startedAt;
        run.durationMs = duration;
        Boolean stored = transactionTemplate.execute(transaction -> {
            if (queueMapper.finish(job.id, status, security.now(), message) != 1) return false;
            runsMapper.insert(run);
            notificationsMapper.insert(run.id, run.userId, run.id, "run",
                "success".equals(status) ? "查寝成功" : "查寝失败", message, run.startedAt);
            return true;
        });
        if (Boolean.TRUE.equals(stored)) sendRunNotification(job.userId, run.id, status, message, run.startedAt, duration);
    }

    void persistRunAndNotification(RunEntity run) {
        transactionTemplate.executeWithoutResult(transaction -> {
            runsMapper.insert(run);
            notificationsMapper.insert(run.id, run.userId, run.id, "run",
                "success".equals(run.status) ? "查寝成功" : "查寝失败", run.message, run.startedAt);
        });
    }

    private String safeErrorMessage(RuntimeException error) {
        if (error instanceof ApiException && error.getMessage() != null && !error.getMessage().isBlank()) return error.getMessage();
        return "查寝执行失败，请检查校园账号状态或稍后重试。";
    }

    private void addNotification(String userId, String type, String title, String message, String createdAt) {
        notificationsMapper.insertEvent(UUID.randomUUID().toString(), userId, type, title, message, createdAt);
    }

    private void sendRunNotification(String userId, String runId, String status, String message,
        String startedAt, int durationMs) {
        try {
            String email = users.findEmailById(userId);
            boolean successful = "success".equals(status);
            String time = Instant.parse(startedAt).atZone(ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String duration = String.format(java.util.Locale.ROOT, "%.1f 秒", durationMs / 1000.0);
            String plain = "查寝任务执行完成。\n\n结果：" + (successful ? "成功" : "失败") +
                "\n执行时间：" + time + "\n任务消息：" + message + "\n执行耗时：" + duration +
                "\n记录编号：" + runId + "\n\n如非本人操作，请登录查寝中心检查账号和定时任务设置。";

            RunNotificationEmail.Image image = successful ? RunNotificationEmail.successImage() : RunNotificationEmail.failureImage(message);
            if (image == null || !new ClassPathResource(image.path()).exists()) {
                SimpleMailMessage fallback = new SimpleMailMessage();
                if (mailFrom != null && !mailFrom.isBlank()) fallback.setFrom(mailFrom);
                fallback.setTo(email);
                fallback.setSubject("查寝任务" + (successful ? "成功" : "失败") + "通知");
                fallback.setText(plain);
                mail.send(fallback);
                return;
            }

            MimeMessage notification = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(notification, true, "UTF-8");
            if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
            helper.setTo(email);
            helper.setSubject(successful ? "查寝日报 / 检查完成 / " + time :
                "查寝任务未完成（查寝失败） / " + image.title() + " / " + time);
            helper.setText(plain, RunNotificationEmail.html(successful, image.title(), message, time, duration, runId));
            helper.addInline("dorm-result-image", new ClassPathResource(image.path()), image.contentType());
            mail.send(notification);
        } catch (RuntimeException error) {
            log.warn("查寝结果通知邮件发送失败，runId={}", runId);
        } catch (MessagingException error) {
            log.warn("查寝结果通知邮件构建失败，runId={}", runId);
        }
    }

    private CampusCredentials loadCredentials(String userId) {
        CredentialEntity credential = credentialsMapper.findEncrypted(userId);
        if (credential == null) throw new ApiException(HttpStatus.CONFLICT, "campus account is not configured");
        try {
            byte[] plain = cipher.decrypt(credential.ciphertext);
            return mapper.readValue(plain, CampusCredentials.class);
        } catch (Exception error) {
            throw new IllegalStateException("credential decryption failed", error);
        }
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String userId(Map<String, Object> session) {
        return (String) session.get("user_id");
    }

    private Map<String, Object> authResponse(String id, String email, String name, String csrf, String currentDeviceId) {
        return Map.of("authenticated", true, "csrf_token", csrf, "current_device_id", currentDeviceId,
            "user", Map.of("id", id, "email", email, "name", name));
    }

    private Map<String, Object> user(Map<String, Object> row) {
        return Map.of("id", row.get("user_id"), "email", row.get("email"), "name", row.get("display_name"));
    }

}
