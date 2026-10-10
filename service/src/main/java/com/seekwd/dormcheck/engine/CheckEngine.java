package com.seekwd.dormcheck.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CheckEngine {
    private static final Logger log = LoggerFactory.getLogger(CheckEngine.class);
    private final ObjectMapper mapper;
    private final String python;
    private final Path worker;
    private final Path engine;

    public CheckEngine(ObjectMapper mapper,
        @Value("${dormcheck.python}") String python,
        @Value("${dormcheck.worker}") String worker,
        @Value("${dormcheck.engine}") String engine) {
        this.mapper = mapper;
        this.python = python;
        this.worker = Path.of(worker).toAbsolutePath().normalize();
        this.engine = Path.of(engine).toAbsolutePath().normalize();
    }

    public Map<String, Object> verify(String account, String password) {
        return invoke("verify", account, password);
    }

    public Map<String, Object> check(String account, String password) {
        return invoke("check", account, password);
    }

    public Map<String, Object> notices(String account, String password) {
        return invoke("notices", account, password);
    }

    public Map<String, Object> academicSchedule(String account, String password) {
        return invoke("academic-schedule", account, password);
    }

    private Map<String, Object> invoke(String action, String account, String password) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python, worker.toString(), action, engine.toString())
                .directory(worker.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> environment = builder.environment();
            String path = environment.getOrDefault("PATH", "");
            String systemRoot = environment.get("SystemRoot");
            String sslCertFile = environment.get("SSL_CERT_FILE");
            String sslCertDir = environment.get("SSL_CERT_DIR");
            environment.clear();
            environment.put("PATH", path);
            environment.put("LANG", "C.UTF-8");
            environment.put("PYTHONNOUSERSITE", "1");
            environment.put("PYTHONDONTWRITEBYTECODE", "1");
            if (systemRoot != null) environment.put("SystemRoot", systemRoot);
            if (sslCertFile != null) environment.put("SSL_CERT_FILE", sslCertFile);
            if (sslCertDir != null) environment.put("SSL_CERT_DIR", sslCertDir);
            process = builder.start();
            byte[] request = mapper.writeValueAsBytes(Map.of("account", account, "password", password));
            try (var input = process.getOutputStream()) {
                input.write(request);
            }
            if (!process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("campus task timed out");
            }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0 || output.length == 0 || output.length > 65_536) {
                throw new IllegalStateException("campus task failed");
            }
            Map<String, Object> result = mapper.readValue(output, Map.class);
            if ("notices".equals(action) || "academic-schedule".equals(action)) {
                Object diagnostic = result.get("diagnostic");
                if (diagnostic != null) log.info("校园教务认证诊断({}): {}", action, diagnostic);
            }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new IllegalStateException("campus task interrupted");
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            if ("verify".equals(action)) return Map.of("verified", false, "error_code", "internal_error", "message", "无法连接校园平台，请稍后重试。");
            if ("notices".equals(action)) return Map.of("ok", false, "error_code", "internal_error", "items", java.util.List.of(), "message", "教务公告获取失败，请稍后重试。");
            if ("academic-schedule".equals(action)) return Map.of("ok", false, "error_code", "internal_error", "items", java.util.List.of(), "message", "我的课表获取失败，请稍后重试。");
            return Map.of("status", "failure", "error_code", "internal_error", "message", "查寝执行中断，未能确认结果，请在校园平台查看。", "duration_ms", 0);
        }
    }
}
