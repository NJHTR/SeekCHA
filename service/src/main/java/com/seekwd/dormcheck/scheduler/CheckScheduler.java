package com.seekwd.dormcheck.scheduler;
import com.seekwd.dormcheck.service.DormCheckService;

import java.time.ZoneId;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.seekwd.dormcheck.mapper.ScheduleMapper;

@Component
public class CheckScheduler {
    private final ScheduleMapper schedules;
    private final TaskScheduler scheduler;
    private final DormCheckService service;
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    public CheckScheduler(ScheduleMapper schedules, TaskScheduler scheduler, DormCheckService service) {
        this.schedules = schedules;
        this.scheduler = scheduler;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        boolean hasDaysColumn = schedules.findColumns().stream().anyMatch(column -> "days_of_week".equals(column.name));
        if (!hasDaysColumn) {
            schedules.addDaysOfWeekColumn();
        }
        for (var row : schedules.findEnabled()) {
            schedule(row.userId, row.runTime, parseDays(row.daysOfWeek), row.timezone);
        }
    }

    public synchronized void schedule(String userId, String runTime, List<Integer> daysOfWeek, String timezone) {
        cancel(userId);
        String[] parts = runTime.split(":");
        String days = daysOfWeek.size() == 7 ? "*" : daysOfWeek.stream().sorted()
            .map(day -> DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT))
            .collect(Collectors.joining(","));
        String cron = "0 " + Integer.parseInt(parts[1]) + " " + Integer.parseInt(parts[0]) + " * * " + days;
        var future = scheduler.schedule(() -> service.runScheduled(userId), new CronTrigger(cron, TimeZone.getTimeZone(ZoneId.of(timezone))));
        jobs.put(userId, future);
    }

    private List<Integer> parseDays(String value) {
        return Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }

    public synchronized void cancel(String userId) {
        ScheduledFuture<?> future = jobs.remove(userId);
        if (future != null) future.cancel(false);
    }
}
