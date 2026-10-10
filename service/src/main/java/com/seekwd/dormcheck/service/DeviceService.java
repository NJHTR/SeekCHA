package com.seekwd.dormcheck.service;
import com.seekwd.dormcheck.security.SecuritySupport;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.seekwd.dormcheck.domain.entity.DeviceEntity;
import com.seekwd.dormcheck.mapper.DeviceMapper;

@Service
public class DeviceService {
    private final DeviceMapper devices;
    private final SecuritySupport security;

    public DeviceService(DeviceMapper devices, SecuritySupport security) {
        this.devices = devices;
        this.security = security;
    }

    public String register(HttpServletRequest request, String userId) {
        String deviceKey = request.getHeader("X-Device-ID");
        if (deviceKey == null || !deviceKey.matches("(?i)[0-9a-f-]{36}")) return null;
        String keyHash = security.hashToken(deviceKey);
        String id = devices.findIdByKey(userId, keyHash);
        String now = security.now();
        String platform = clean(request.getHeader("X-Device-Platform"), 80);
        String userAgent = clean(request.getHeader("User-Agent"), 500);
        String language = clean(request.getHeader("X-Device-Language"), 80);
        String timezone = clean(request.getHeader("X-Device-Timezone"), 80);
        String screen = clean(request.getHeader("X-Device-Screen"), 24);
        Integer threads = boundedInteger(request.getHeader("X-Device-CPU"), 1, 512);
        Integer memory = boundedInteger(request.getHeader("X-Device-Memory"), 1, 1024);
        String name = deviceName(platform, userAgent);
        if (id == null) {
            id = UUID.randomUUID().toString();
            DeviceEntity device = new DeviceEntity();
            device.id = id;
            device.userId = userId;
            device.deviceKeyHash = keyHash;
            device.deviceName = name;
            device.platform = platform;
            device.userAgent = userAgent;
            device.language = language;
            device.timezone = timezone;
            device.screenSize = screen;
            device.hardwareThreads = threads;
            device.deviceMemory = memory;
            device.createdAt = now;
            device.lastSeenAt = now;
            devices.insert(device);
        } else {
            DeviceEntity device = new DeviceEntity();
            device.id = id;
            device.userId = userId;
            device.deviceName = name;
            device.platform = platform;
            device.userAgent = userAgent;
            device.language = language;
            device.timezone = timezone;
            device.screenSize = screen;
            device.hardwareThreads = threads;
            device.deviceMemory = memory;
            device.lastSeenAt = now;
            devices.update(device);
        }
        return id;
    }

    public String currentId(HttpServletRequest request, String userId) {
        String deviceKey = request.getHeader("X-Device-ID");
        if (deviceKey == null || !deviceKey.matches("(?i)[0-9a-f-]{36}")) return "";
        String id = devices.findCurrentId(userId, security.hashToken(deviceKey));
        return id == null ? "" : id;
    }

    private String clean(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.substring(0, Math.min(max, normalized.length()));
    }

    private Integer boundedInteger(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            return number >= min && number <= max ? number : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String deviceName(String platform, String userAgent) {
        String browser = "浏览器";
        if (userAgent.contains("Edg/")) browser = "Microsoft Edge";
        else if (userAgent.contains("Firefox/")) browser = "Firefox";
        else if (userAgent.contains("Chrome/")) browser = "Chrome";
        else if (userAgent.contains("Safari/")) browser = "Safari";
        return (platform.isBlank() ? "未知设备" : platform) + " · " + browser;
    }
}
