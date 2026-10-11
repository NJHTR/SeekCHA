package com.seekwd.dormcheck.service;

import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final AdminAuthService adminAuth;
    private final SecuritySupport security;
    private final List<Network> allowedNetworks;

    public AdminService(JdbcTemplate jdbc, AdminAuthService adminAuth, SecuritySupport security,
        @Value("${dormcheck.admin-allowed-cidrs:127.0.0.1/32,::1/128,192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10}") String allowedCidrs) {
        this.jdbc = jdbc;
        this.adminAuth = adminAuth;
        this.security = security;
        this.allowedNetworks = Arrays.stream(allowedCidrs.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .map(Network::parse).toList();
    }

    public Map<String, Object> me(HttpServletRequest request) {
        return requireAdmin(request, "admin.me");
    }

    public Map<String, Object> overview(HttpServletRequest request) {
        Map<String, Object> admin = requireAdmin(request, "admin.overview");
        Map<String, Object> users = jdbc.queryForMap("SELECT COUNT(*) total, " +
            "SUM(CASE WHEN c.user_id IS NOT NULL THEN 1 ELSE 0 END) configured, " +
            "SUM(CASE WHEN c.verified_at IS NOT NULL THEN 1 ELSE 0 END) verified, " +
            "SUM(CASE WHEN s.enabled=1 THEN 1 ELSE 0 END) scheduled FROM users u " +
            "LEFT JOIN credentials c ON c.user_id=u.id LEFT JOIN schedules s ON s.user_id=u.id");
        Map<String, Object> runs = jdbc.queryForMap("SELECT COUNT(*) total, " +
            "SUM(CASE WHEN status='success' THEN 1 ELSE 0 END) successes, " +
            "SUM(CASE WHEN status='failure' THEN 1 ELSE 0 END) failures FROM runs WHERE started_at >= ?", dayStart());
        Map<String, Object> queue = jdbc.queryForMap("SELECT " +
            "SUM(CASE WHEN status='queued' THEN 1 ELSE 0 END) queued, " +
            "SUM(CASE WHEN status='running' THEN 1 ELSE 0 END) running, " +
            "SUM(CASE WHEN status='retry_wait' THEN 1 ELSE 0 END) retry_wait FROM check_jobs " +
            "WHERE status IN ('queued','running','retry_wait')");
        Map<String, Object> featureUse = jdbc.queryForMap("SELECT " +
            "SUM(CASE WHEN feature='schedule' THEN 1 ELSE 0 END) timetable, " +
            "SUM(CASE WHEN feature='notices' THEN 1 ELSE 0 END) notices FROM feature_usage_events WHERE created_at >= ?", dayStart());
        return Map.of("admin", admin, "users", nullSafe(users), "runs_today", nullSafe(runs),
            "queue", nullSafe(queue), "feature_use_today", nullSafe(featureUse));
    }

    public Map<String, Object> users(HttpServletRequest request, int page, String query) {
        requireAdmin(request, "admin.users");
        int safePage = Math.max(1, Math.min(page, 100_000));
        String term = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        int size = 25;
        List<Map<String, Object>> rows = jdbc.query("SELECT u.id,u.email,u.display_name,u.created_at, " +
            "c.user_id IS NOT NULL configured,c.verified_at,s.enabled schedule_enabled, " +
            "(SELECT r.status FROM runs r WHERE r.user_id=u.id ORDER BY r.started_at DESC LIMIT 1) last_status, " +
            "(SELECT r.started_at FROM runs r WHERE r.user_id=u.id ORDER BY r.started_at DESC LIMIT 1) last_run " +
            "FROM users u LEFT JOIN credentials c ON c.user_id=u.id LEFT JOIN schedules s ON s.user_id=u.id " +
            "WHERE (?='' OR lower(u.email) LIKE ? OR lower(u.display_name) LIKE ?) " +
            "ORDER BY u.created_at DESC LIMIT ? OFFSET ?", (rs, row) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getString("id")); item.put("email", maskEmail(rs.getString("email")));
                item.put("name", rs.getString("display_name")); item.put("registered_at", rs.getString("created_at"));
                item.put("credential_configured", rs.getBoolean("configured")); item.put("credential_verified", rs.getString("verified_at") != null);
                item.put("schedule_enabled", rs.getBoolean("schedule_enabled")); item.put("last_run_status", rs.getString("last_status"));
                item.put("last_run_at", rs.getString("last_run"));
                return item;
            }, term, "%" + term + "%", "%" + term + "%", size, (safePage - 1) * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE (?='' OR lower(email) LIKE ? OR lower(display_name) LIKE ?)",
            Long.class, term, "%" + term + "%", "%" + term + "%");
        return Map.of("items", rows, "page", safePage, "page_size", size, "total", total);
    }

    public Map<String, Object> runs(HttpServletRequest request, int page, String status) {
        requireAdmin(request, "admin.runs");
        if (!List.of("all", "success", "failure").contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid status filter");
        int safePage = Math.max(1, Math.min(page, 100_000));
        int size = 25;
        List<Map<String, Object>> rows = jdbc.query("SELECT r.id,u.email,u.display_name,r.status,r.message,r.started_at,r.duration_ms " +
            "FROM runs r JOIN users u ON u.id=r.user_id WHERE (?='all' OR r.status=?) " +
            "ORDER BY r.started_at DESC,r.id DESC LIMIT ? OFFSET ?", (rs, row) -> Map.of(
                "id", rs.getString("id"), "email", maskEmail(rs.getString("email")), "name", rs.getString("display_name"),
                "status", rs.getString("status"), "message", safeMessage(rs.getString("message")),
                "time", rs.getString("started_at"), "duration_ms", rs.getInt("duration_ms")), status, status, size, (safePage - 1) * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE (?='all' OR status=?)", Long.class, status, status);
        return Map.of("items", rows, "page", safePage, "page_size", size, "total", total);
    }

    public Map<String, Object> queue(HttpServletRequest request) {
        requireAdmin(request, "admin.queue");
        List<Map<String, Object>> rows = jdbc.query("SELECT j.id,u.email,u.display_name,j.trigger_type,j.status,j.attempts," +
            "j.max_attempts,j.created_at,j.not_before,j.started_at,j.last_error FROM check_jobs j JOIN users u ON u.id=j.user_id " +
            "WHERE j.status IN ('queued','running','retry_wait') ORDER BY j.created_at LIMIT 200", (rs, row) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getString("id")); item.put("email", maskEmail(rs.getString("email"))); item.put("name", rs.getString("display_name"));
                item.put("trigger", rs.getString("trigger_type")); item.put("status", rs.getString("status")); item.put("attempts", rs.getInt("attempts"));
                item.put("max_attempts", rs.getInt("max_attempts")); item.put("created_at", rs.getString("created_at"));
                item.put("not_before", rs.getString("not_before")); item.put("started_at", valueOrEmpty(rs.getString("started_at")));
                item.put("reason", safeMessage(rs.getString("last_error"))); return item;
            });
        return Map.of("items", rows);
    }

    public Map<String, Object> audit(HttpServletRequest request) {
        Map<String, Object> admin = requireAdmin(request, "admin.audit");
        if (!"owner".equals(admin.get("role"))) throw new ApiException(HttpStatus.FORBIDDEN, "owner role required");
        List<Map<String, Object>> rows = jdbc.query("SELECT a.action,a.remote_address,a.created_at,u.display_name FROM admin_audit_logs a " +
            "LEFT JOIN admin_users u ON u.id=a.admin_user_id ORDER BY a.created_at DESC LIMIT 100", (rs, row) -> Map.of(
                "action", safeMessage(rs.getString("action")), "remote_address", rs.getString("remote_address"),
                "time", rs.getString("created_at"), "admin", valueOrEmpty(rs.getString("display_name"))));
        return Map.of("items", rows);
    }

    public Map<String, Object> featureUsage(HttpServletRequest request, int page, String feature) {
        requireAdmin(request, "admin.features");
        if (!List.of("all", "notices", "schedule").contains(feature)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid feature filter");
        int safePage = Math.max(1, Math.min(page, 100_000));
        int size = 25;
        List<Map<String, Object>> rows = jdbc.query("SELECT e.feature,e.result,e.created_at,u.display_name,u.email " +
            "FROM feature_usage_events e JOIN users u ON u.id=e.user_id WHERE (?='all' OR e.feature=?) " +
            "ORDER BY e.created_at DESC LIMIT ? OFFSET ?", (rs, row) -> Map.of(
                "feature", rs.getString("feature"), "result", rs.getString("result"), "time", rs.getString("created_at"),
                "name", rs.getString("display_name"), "email", maskEmail(rs.getString("email"))), feature, feature, size, (safePage - 1) * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM feature_usage_events WHERE (?='all' OR feature=?)", Long.class, feature, feature);
        return Map.of("items", rows, "page", safePage, "page_size", size, "total", total);
    }

    public void recordFeatureUse(String userId, String feature, boolean success) {
        jdbc.update("INSERT INTO feature_usage_events(id,user_id,feature,result,created_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(), userId, feature, success ? "success" : "failure", security.now());
    }

    private Map<String, Object> requireAdmin(HttpServletRequest request, String action) {
        if (allowedNetworks.stream().noneMatch(network -> network.contains(request.getRemoteAddr()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not found");
        }
        Map<String, Object> session = adminAuth.require(request);
        String userId = (String) session.get("admin_id");
        String role = (String) session.get("role");
        if ("analyst".equals(role) && !List.of("admin.me", "admin.overview").contains(action)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "insufficient role");
        }
        jdbc.update("INSERT INTO admin_audit_logs(id,admin_user_id,action,remote_address,created_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(), userId, action, request.getRemoteAddr(), security.now());
        return Map.of("role", role, "name", session.get("display_name"), "user_id", userId);
    }

    private String dayStart() { return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toString(); }
    private static Map<String, Object> nullSafe(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(); source.forEach((key, value) -> result.put(key, value == null ? 0 : value)); return result;
    }
    private static String valueOrEmpty(String value) { return value == null ? "" : value; }
    private static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@'); if (at < 1) return "***";
        String local = email.substring(0, at); return local.substring(0, Math.min(2, local.length())) + "***" + email.substring(at);
    }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "";
        String safe = message.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱已隐藏]")
            .replaceAll("(?i)(password|passwd|cookie|token)\\s*[:=]\\s*[^\\s,;]+", "$1=[已隐藏]");
        return safe.length() > 240 ? safe.substring(0, 240) + "…" : safe;
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            try {
                String[] parts = value.split("/", 2); byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
                if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("invalid admin CIDR: " + value);
                return new Network(address, prefix);
            } catch (Exception error) { throw new IllegalArgumentException("invalid admin CIDR: " + value, error); }
        }
        boolean contains(String candidate) {
            try {
                byte[] other = InetAddress.getByName(candidate).getAddress(); if (other.length != address.length) return false;
                int whole = prefix / 8, bits = prefix % 8;
                for (int i = 0; i < whole; i++) if (address[i] != other[i]) return false;
                if (bits == 0) return true;
                int mask = 0xff << (8 - bits);
                return (address[whole] & mask) == (other[whole] & mask);
            } catch (Exception error) { return false; }
        }
    }
}
