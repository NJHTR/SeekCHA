package com.seekwd.dormcheck.service;

import com.seekwd.dormcheck.domain.entity.CheckJobEntity;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.mapper.CheckQueueMapper;
import com.seekwd.dormcheck.mapper.CredentialMapper;
import com.seekwd.dormcheck.mapper.ScheduleMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CheckQueueService {
    private static final Logger log = LoggerFactory.getLogger(CheckQueueService.class);
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DAILY_ATTEMPTS = 3;
    private static final int RETRY_SECONDS = 300;
    private final CheckQueueMapper jobs;
    private final CredentialMapper credentials;
    private final ScheduleMapper schedules;
    private final CampusAccessGuard access;
    private final ObjectProvider<DormCheckService> service;
    private final ReentrantLock workerLock = new ReentrantLock();
    private final boolean enabled;
    private volatile boolean ready;

    public CheckQueueService(CheckQueueMapper jobs, CredentialMapper credentials, ScheduleMapper schedules,
        CampusAccessGuard access, ObjectProvider<DormCheckService> service,
        @Value("${dormcheck.queue-worker-enabled:true}") boolean enabled) {
        this.jobs = jobs; this.credentials = credentials; this.schedules = schedules;
        this.access = access; this.service = service; this.enabled = enabled;
    }

    private String timestamp(Instant value) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }

    public synchronized Map<String, Object> enqueue(String userId, String trigger) {
        CheckJobEntity active = jobs.findActive(userId);
        if (active != null) return acknowledgement(active, true, "已有查寝任务在队列中。");
        Instant now = Instant.now();
        Instant dayStart = now.atZone(CAMPUS_ZONE).toLocalDate().atStartOfDay(CAMPUS_ZONE).toInstant();
        CheckJobEntity success = jobs.findSuccessSince(userId, timestamp(dayStart));
        if (success != null) return acknowledgement(success, true, "今天的查寝任务已完成，无需重复提交。");
        if (credentials.findVerifiedAt(userId) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "请先配置并验证校园账号。");
        }
        int remaining = DAILY_ATTEMPTS - jobs.attemptsSince(userId, trigger, timestamp(dayStart));
        if (remaining <= 0) {
            String kind = "scheduled".equals(trigger) ? "定时查寝" : "手动查寝";
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "今天的" + kind + "已达到 3 次尝试上限，请在校园平台确认结果。");
        }
        CheckJobEntity job = new CheckJobEntity();
        job.id = UUID.randomUUID().toString(); job.userId = userId; job.triggerType = trigger;
        job.status = "queued"; job.maxAttempts = remaining; job.createdAt = timestamp(now);
        Instant earliest = now;
        String lastStarted = jobs.lastStartedAt(userId);
        if (lastStarted != null) {
            Instant gapEnd = Instant.parse(lastStarted).plusSeconds(RETRY_SECONDS);
            if (gapEnd.isAfter(earliest)) earliest = gapEnd;
        }
        job.notBefore = timestamp(earliest);
        job.expiresAt = timestamp(dayStart.atZone(CAMPUS_ZONE).plusDays(1).toInstant());
        try { jobs.insert(job); }
        catch (DuplicateKeyException conflict) {
            active = jobs.findActive(userId);
            if (active == null) throw conflict;
            return acknowledgement(active, true, "已有查寝任务在队列中。");
        }
        return acknowledgement(job, false, "已加入查寝队列，将按顺序执行。");
    }

    private Map<String, Object> acknowledgement(CheckJobEntity job, boolean duplicate, String message) {
        return Map.of("id", job.id, "status", job.status, "message", message, "duplicate", duplicate);
    }

    public Map<String, Object> status(String userId) {
        CheckJobEntity job = jobs.findActive(userId);
        if (job == null) return Map.of("active", false);
        var state = jobs.accessState();
        long available = Math.max(state.notBefore, state.cooldownUntil);
        Map<String, Object> status = new HashMap<>();
        status.put("active", true); status.put("id", job.id); status.put("status", job.status);
        status.put("attempts", job.attempts); status.put("max_attempts", job.maxAttempts);
        status.put("not_before", timestamp(Instant.ofEpochMilli(Math.max(available, Instant.parse(job.notBefore).toEpochMilli()))));
        status.put("paused", state.cooldownUntil > Instant.now().toEpochMilli());
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (CheckJobEntity interrupted : jobs.findRunning()) {
            interrupted.startedAt = interrupted.startedAt == null ? timestamp(Instant.now()) : interrupted.startedAt;
            finish(interrupted, "failure", "服务重启时任务中断，校园平台是否已收到请求无法确认。为避免重复提交，本次未自动重试。", 0);
        }
        ready = true;
    }

    public void cancelScheduled(String userId) { jobs.cancelScheduled(userId, timestamp(Instant.now())); }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        if (enabled && ready) processOne();
    }

    public void processOne() {
        if (!workerLock.tryLock()) return;
        try {
            for (CheckJobEntity interrupted : jobs.findRunning()) {
                if (interrupted.startedAt != null && Instant.parse(interrupted.startedAt).isBefore(Instant.now().minusSeconds(180))) {
                    finish(interrupted, "failure", "任务执行中断，未能确认校园平台结果。为避免重复提交，本次未自动重试。", 0);
                }
            }
            CheckJobEntity job = jobs.nextDue(timestamp(Instant.now()));
            if (job == null) return;
            if (!Instant.parse(job.expiresAt).isAfter(Instant.now())) {
                finish(job, "failure", "任务已超过当天查寝期限，请在校园平台确认结果。", 0);
                return;
            }
            if (job.attempts >= job.maxAttempts) {
                finish(job, "failure", "已达到查寝尝试上限；上次执行中断，请在校园平台确认结果。", 0);
                return;
            }
            var schedule = schedules.findByUserId(job.userId);
            if (credentials.findVerifiedAt(job.userId) == null || ("scheduled".equals(job.triggerType) && (schedule == null || schedule.enabled != 1))) {
                finish(job, "failure", "校园账号未验证或定时任务已暂停，本次任务已停止。", 0);
                return;
            }
            Map<String, Object> result = access.call(() -> attempt(job));
            if (result.isEmpty()) return;
            String message = String.valueOf(result.getOrDefault("message", "查寝未完成，请在校园平台确认结果。"));
            String code = String.valueOf(result.getOrDefault("error_code", "unknown"));
            boolean success = "success".equals(result.get("status"));
            if (!success && job.attempts < job.maxAttempts && ("network".equals(code) || "server_error".equals(code))) {
                jobs.retry(job.id, timestamp(Instant.now().plusSeconds(RETRY_SECONDS)), message);
            } else {
                finish(job, success ? "success" : "failure", message, ((Number) result.getOrDefault("duration_ms", 0)).intValue());
            }
        } catch (CampusAccessGuard.AccessBusyException busy) {
            // No attempt is consumed until the global campus lease is available.
        } catch (RuntimeException error) {
            log.error("could not process dorm check queue", error);
        } finally { workerLock.unlock(); }
    }

    private Map<String, Object> attempt(CheckJobEntity job) {
        String startedAt = timestamp(Instant.now());
        if (jobs.claim(job.id, startedAt) != 1) return Map.of();
        job.startedAt = startedAt;
        job.attempts++;
        try { return service.getObject().runQueuedAttempt(job.userId); }
        catch (RuntimeException error) {
            log.warn("dorm check attempt failed for job {}", job.id);
            return Map.of("status", "failure", "error_code", "internal_error", "message", "查寝执行异常，请在校园平台确认结果。", "duration_ms", 0);
        }
    }

    private void finish(CheckJobEntity job, String status, String message, int duration) {
        service.getObject().finishQueuedCheck(job, status, message, duration);
    }
}
