package com.seekwd.dormcheck.controller;
import com.seekwd.dormcheck.service.DormCheckService;
import com.seekwd.dormcheck.service.impl.AuthSecurityService;
import com.seekwd.dormcheck.service.SessionService;
import com.seekwd.dormcheck.api.dto.AuthRequest;
import com.seekwd.dormcheck.api.dto.CredentialRequest;
import com.seekwd.dormcheck.api.dto.EmailCodeRequest;
import com.seekwd.dormcheck.api.dto.LoginVerification;
import com.seekwd.dormcheck.api.dto.ScheduleRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DormCheckController {
    private final DormCheckService service;
    private final SessionService sessions;
    private final AuthSecurityService authSecurity;

    public DormCheckController(DormCheckService service, SessionService sessions, AuthSecurityService authSecurity) {
        this.service = service;
        this.sessions = sessions;
        this.authSecurity = authSecurity;
    }

    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @PostMapping("/auth/register")
    Map<String, Object> register(@Valid @RequestBody AuthRequest payload,
        HttpServletRequest request, HttpServletResponse response) {
        return service.register(payload, request, response);
    }

    @PostMapping("/auth/register-code")
    Map<String, Boolean> registrationCode(@Valid @RequestBody EmailCodeRequest payload, HttpServletRequest request) {
        service.sendRegistrationCode(payload, request);
        return Map.of("sent", true);
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody AuthRequest payload, HttpServletRequest request) {
        return service.login(payload, request);
    }

    @PostMapping("/auth/verify")
    Map<String, Object> verifyLogin(@Valid @RequestBody LoginVerification payload,
        HttpServletRequest request, HttpServletResponse response) {
        return service.verifyLogin(payload, request, response);
    }

    @GetMapping("/auth/session")
    Map<String, Object> session(HttpServletRequest request) {
        return service.session(request);
    }

    @PostMapping("/auth/logout")
    Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> session = requireMutationSession(request);
        sessions.requireCsrf(request, session);
        service.logout(request, response);
        return Map.of("ok", true);
    }

    @GetMapping("/auth/devices")
    java.util.List<Map<String, Object>> devices(HttpServletRequest request) {
        return service.devices(sessions.require(request));
    }

    @DeleteMapping("/auth/devices/{deviceId}")
    Map<String, Boolean> revokeDevice(@PathVariable String deviceId, HttpServletRequest request) {
        Map<String, Object> session = requireMutationSession(request);
        return Map.of("revoked", service.revokeDevice(session, deviceId));
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard(HttpServletRequest request) {
        return service.dashboard(sessions.require(request));
    }

    @GetMapping("/checks")
    Map<String, Object> checks(@RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "all") String status, HttpServletRequest request) {
        return service.checks(sessions.require(request), page, status);
    }

    @GetMapping("/notifications")
    Map<String, Object> notifications(HttpServletRequest request) {
        return service.notifications(sessions.require(request));
    }

    @GetMapping("/academic/notices")
    Map<String, Object> academicNotices(@RequestParam(defaultValue = "false") boolean refresh, HttpServletRequest request) {
        return service.academicNotices(sessions.require(request), request, refresh);
    }

    @GetMapping("/academic/schedule")
    Map<String, Object> academicSchedule(@RequestParam(defaultValue = "false") boolean refresh, HttpServletRequest request) {
        return service.academicSchedule(sessions.require(request), request, refresh);
    }

    @PutMapping("/notifications/read")
    Map<String, Object> markNotificationsRead(HttpServletRequest request) {
        return service.markNotificationsRead(requireMutationSession(request));
    }

    @PutMapping("/notifications/{notificationId}/read")
    Map<String, Object> markNotificationRead(@PathVariable String notificationId, HttpServletRequest request) {
        return service.markNotificationRead(requireMutationSession(request), notificationId);
    }

    @GetMapping("/auth/totp")
    Map<String, Boolean> totpStatus(HttpServletRequest request) {
        Map<String, Object> session = sessions.require(request);
        return Map.of("enabled", authSecurity.totpEnabled((String) session.get("user_id")));
    }

    @PostMapping("/auth/totp/setup")
    Map<String, String> beginTotp(HttpServletRequest request) {
        return authSecurity.beginTotpSetup(requireMutationSession(request));
    }

    @PostMapping("/auth/totp/confirm")
    Map<String, Boolean> confirmTotp(@Valid @RequestBody TotpRequest payload, HttpServletRequest request) {
        return authSecurity.confirmTotp(requireMutationSession(request), payload.code());
    }

    @PutMapping("/credentials")
    Map<String, Object> saveCredentials(@Valid @RequestBody CredentialRequest payload, HttpServletRequest request) {
        Map<String, Object> session = requireMutationSession(request);
        return service.saveCredentials(payload, session);
    }

    @PostMapping("/credentials/verify")
    Map<String, Object> verifyCredentials(HttpServletRequest request) {
        Map<String, Object> session = requireMutationSession(request);
        return service.verifyCredentials(session, request);
    }

    @DeleteMapping("/credentials")
    Map<String, Object> deleteCredentials(HttpServletRequest request) {
        return service.deleteCredentials(requireMutationSession(request));
    }

    @PutMapping("/schedule")
    Map<String, Object> saveSchedule(@Valid @RequestBody ScheduleRequest payload, HttpServletRequest request) {
        return service.saveSchedule(payload, requireMutationSession(request));
    }

    @PostMapping("/checks")
    Map<String, Object> runNow(HttpServletRequest request) {
        return service.runNow(requireMutationSession(request), request);
    }

    @GetMapping("/checks/queue")
    Map<String, Object> checkQueueStatus(HttpServletRequest request) {
        return service.checkQueueStatus(sessions.require(request));
    }

    private Map<String, Object> requireMutationSession(HttpServletRequest request) {
        Map<String, Object> session = sessions.require(request);
        sessions.requireCsrf(request, session);
        return session;
    }

    public record TotpRequest(@jakarta.validation.constraints.Pattern(regexp="\\d{6}") String code) {}
}
