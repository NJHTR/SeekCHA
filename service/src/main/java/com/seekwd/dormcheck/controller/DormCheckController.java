package com.seekwd.dormcheck.controller;
import com.seekwd.dormcheck.service.DormCheckService;
import com.seekwd.dormcheck.service.impl.AuthSecurityService;
import com.seekwd.dormcheck.service.SessionService;
import com.seekwd.dormcheck.service.AdminService;
import com.seekwd.dormcheck.service.AdminAuthService;
import com.seekwd.dormcheck.service.XiangqiService;
import com.seekwd.dormcheck.api.dto.AuthRequest;
import com.seekwd.dormcheck.api.dto.CredentialRequest;
import com.seekwd.dormcheck.api.dto.EmailCodeRequest;
import com.seekwd.dormcheck.api.dto.LoginVerification;
import com.seekwd.dormcheck.api.dto.ScheduleRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final AdminService admin;
    private final AdminAuthService adminAuth;
    private final XiangqiService xiangqi;

    public DormCheckController(DormCheckService service, SessionService sessions, AuthSecurityService authSecurity,
        AdminService admin, AdminAuthService adminAuth, XiangqiService xiangqi) {
        this.service = service;
        this.sessions = sessions;
        this.authSecurity = authSecurity;
        this.admin = admin;
        this.adminAuth = adminAuth;
        this.xiangqi = xiangqi;
    }

    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @GetMapping("/admin/me")
    Map<String, Object> adminMe(HttpServletRequest request) { return admin.me(request); }

    @GetMapping("/admin/auth/session")
    Map<String, Object> adminSession(HttpServletRequest request) { return adminAuth.session(request); }

    @PostMapping("/admin/auth/bootstrap")
    Map<String, Object> adminBootstrap(@Valid @RequestBody AdminBootstrapRequest payload, HttpServletRequest request) {
        return adminAuth.bootstrap(payload.token(), payload.email(), payload.name(), payload.password(), request);
    }

    @PostMapping("/admin/auth/login/code")
    Map<String, Boolean> requestAdminLoginCode(@Valid @RequestBody AdminLoginCodeRequest payload, HttpServletRequest request) {
        adminAuth.requestLoginCode(payload.email(), payload.password(), request);
        return Map.of("sent", true);
    }

    @PostMapping("/admin/auth/login")
    Map<String, Object> adminLogin(@Valid @RequestBody AdminLoginRequest payload, HttpServletRequest request, HttpServletResponse response) {
        return adminAuth.login(payload.email(), payload.password(), payload.code(), request, response);
    }

    @PostMapping("/admin/auth/logout")
    Map<String, Boolean> adminLogout(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> session = adminAuth.require(request);
        adminAuth.requireCsrf(request, session);
        adminAuth.logout(request, response);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/accounts")
    Map<String, Object> createAdminAccount(@Valid @RequestBody AdminAccountRequest payload, HttpServletRequest request) {
        Map<String, Object> session = adminAuth.require(request);
        adminAuth.requireCsrf(request, session);
        return adminAuth.createAccount(session, payload.email(), payload.name(), payload.password(), payload.role(), request);
    }

    @GetMapping("/admin/overview")
    Map<String, Object> adminOverview(HttpServletRequest request) { return admin.overview(request); }

    @GetMapping("/admin/users")
    Map<String, Object> adminUsers(@RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "") String q, HttpServletRequest request) { return admin.users(request, page, q); }

    @GetMapping("/admin/runs")
    Map<String, Object> adminRuns(@RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "all") String range,
        HttpServletRequest request) { return admin.runs(request, page, status, range); }

    @GetMapping("/admin/queue")
    Map<String, Object> adminQueue(HttpServletRequest request) { return admin.queue(request); }

    @GetMapping("/admin/audit")
    Map<String, Object> adminAudit(HttpServletRequest request) { return admin.audit(request); }

    @GetMapping("/admin/features")
    Map<String, Object> adminFeatures(@RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "all") String feature, HttpServletRequest request) { return admin.featureUsage(request, page, feature); }

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

    @GetMapping("/xiangqi/games")
    java.util.List<Map<String, Object>> xiangqiGames(HttpServletRequest request) {
        return xiangqi.list(sessions.require(request));
    }

    @GetMapping("/xiangqi/profile")
    Map<String, Object> xiangqiProfile(HttpServletRequest request) {
        return xiangqi.profile(sessions.require(request));
    }

    @PostMapping("/xiangqi/match")
    Map<String, Object> startXiangqiMatch(HttpServletRequest request) {
        return xiangqi.match(requireMutationSession(request), request);
    }

    @GetMapping("/xiangqi/match")
    Map<String, Object> xiangqiMatchStatus(HttpServletRequest request) {
        return xiangqi.matchStatus(sessions.require(request));
    }

    @DeleteMapping("/xiangqi/match")
    Map<String, Object> cancelXiangqiMatch(HttpServletRequest request) {
        return xiangqi.cancelMatch(requireMutationSession(request));
    }

    @PostMapping("/xiangqi/rewards/ad/start")
    Map<String, Object> startXiangqiAd(HttpServletRequest request) {
        return xiangqi.startAd(requireMutationSession(request), request);
    }

    @PostMapping("/xiangqi/rewards/ad/{sessionId}/claim")
    Map<String, Object> claimXiangqiAd(@PathVariable String sessionId, HttpServletRequest request) {
        return xiangqi.claimAd(sessionId, requireMutationSession(request));
    }

    @PostMapping("/xiangqi/rewards/relief")
    Map<String, Object> claimXiangqiRelief(HttpServletRequest request) {
        return xiangqi.claimRelief(requireMutationSession(request));
    }

    @PostMapping("/xiangqi/games")
    Map<String, Object> createXiangqiGame(HttpServletRequest request) {
        return xiangqi.create(requireMutationSession(request), request);
    }

    @PostMapping("/xiangqi/games/join")
    Map<String, Object> joinXiangqiGame(@Valid @RequestBody XiangqiJoinRequest payload, HttpServletRequest request) {
        return xiangqi.join(payload.roomCode(), requireMutationSession(request), request);
    }

    @GetMapping("/xiangqi/games/{gameId}")
    Map<String, Object> xiangqiGame(@PathVariable String gameId, HttpServletRequest request) {
        return xiangqi.get(gameId, sessions.require(request));
    }

    @PostMapping("/xiangqi/games/{gameId}/moves")
    Map<String, Object> xiangqiMove(@PathVariable String gameId, @Valid @RequestBody XiangqiMoveRequest payload,
        HttpServletRequest request) {
        return xiangqi.move(gameId, payload.from(), payload.to(), payload.expectedVersion(),
            requireMutationSession(request), request);
    }

    @PostMapping("/xiangqi/games/{gameId}/resign")
    Map<String, Object> resignXiangqiGame(@PathVariable String gameId,
        @Valid @RequestBody XiangqiVersionRequest payload, HttpServletRequest request) {
        return xiangqi.resign(gameId, payload.expectedVersion(), requireMutationSession(request));
    }

    @PostMapping("/xiangqi/games/{gameId}/draw")
    Map<String, Object> xiangqiDraw(@PathVariable String gameId, @Valid @RequestBody XiangqiDrawRequest payload,
        HttpServletRequest request) {
        return xiangqi.draw(gameId, payload.action(), payload.expectedVersion(), requireMutationSession(request));
    }

    @GetMapping("/xiangqi/games/{gameId}/messages")
    java.util.List<Map<String, Object>> xiangqiMessages(@PathVariable String gameId, HttpServletRequest request) {
        return xiangqi.messages(gameId, sessions.require(request));
    }

    @PostMapping("/xiangqi/games/{gameId}/messages")
    Map<String, Object> postXiangqiMessage(@PathVariable String gameId,
        @Valid @RequestBody XiangqiMessageRequest payload, HttpServletRequest request) {
        return xiangqi.postMessage(gameId, payload.body(), requireMutationSession(request), request);
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
        return authSecurity.beginTotpSetup(requireMutationSession(request), request.getRemoteAddr());
    }

    @PostMapping("/auth/totp/confirm")
    Map<String, Boolean> confirmTotp(@Valid @RequestBody TotpRequest payload, HttpServletRequest request) {
        return authSecurity.confirmTotp(requireMutationSession(request), payload.code(), request.getRemoteAddr());
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

    public record TotpRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp="\\d{6}") String code) {}
    public record AdminBootstrapRequest(@NotBlank @Size(max=256) String token,
        @Email @NotBlank @Size(max=254) String email,
        @Size(max=80) String name, @Size(min=16, max=256) String password) {}
    public record AdminLoginRequest(@Email @NotBlank @Size(max=254) String email,
        @NotBlank @Size(max=256) String password, @NotBlank @jakarta.validation.constraints.Pattern(regexp="\\d{6}") String code) {}
    public record AdminLoginCodeRequest(@Email @NotBlank @Size(max=254) String email,
        @NotBlank @Size(max=256) String password) {}
    public record AdminAccountRequest(@Email @NotBlank @Size(max=254) String email,
        @Size(max=80) String name, @Size(min=16, max=256) String password,
        @jakarta.validation.constraints.Pattern(regexp="owner|operator|analyst") String role) {}
    public record XiangqiJoinRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9]{6}") String roomCode) {}
    public record XiangqiMoveRequest(
        @NotBlank @jakarta.validation.constraints.Pattern(regexp="[a-iA-I][0-9]") String from,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp="[a-iA-I][0-9]") String to,
        @jakarta.validation.constraints.Min(1) int expectedVersion) {}
    public record XiangqiVersionRequest(@jakarta.validation.constraints.Min(1) int expectedVersion) {}
    public record XiangqiDrawRequest(
        @NotBlank @jakarta.validation.constraints.Pattern(regexp="offer|accept|decline") String action,
        @jakarta.validation.constraints.Min(1) int expectedVersion) {}
    public record XiangqiMessageRequest(@NotBlank @Size(max=80) String body) {}
}
