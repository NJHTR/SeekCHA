package com.seekwd.dormcheck.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.seekwd.dormcheck.api.dto.AuthRequest;
import com.seekwd.dormcheck.api.dto.CredentialRequest;
import com.seekwd.dormcheck.api.dto.EmailCodeRequest;
import com.seekwd.dormcheck.api.dto.LoginVerification;
import com.seekwd.dormcheck.api.dto.ScheduleRequest;
import java.util.List;
import java.util.Map;

public interface DormCheckService {
    Map<String, Object> register(AuthRequest payload, HttpServletRequest request, HttpServletResponse response);
    void sendRegistrationCode(EmailCodeRequest payload, HttpServletRequest request);
    Map<String, Object> login(AuthRequest payload, HttpServletRequest request);
    Map<String, Object> verifyLogin(LoginVerification payload, HttpServletRequest request, HttpServletResponse response);
    Map<String, Object> session(HttpServletRequest request);
    void logout(HttpServletRequest request, HttpServletResponse response);
    List<Map<String, Object>> devices(Map<String, Object> session);
    boolean revokeDevice(Map<String, Object> session, String deviceId);
    Map<String, Object> dashboard(Map<String, Object> session);
    Map<String, Object> checks(Map<String, Object> session, int requestedPage, String status);
    Map<String, Object> notifications(Map<String, Object> session);
    Map<String, Object> academicNotices(Map<String, Object> session, HttpServletRequest request);
    Map<String, Object> academicNotices(Map<String, Object> session, HttpServletRequest request, boolean forceRefresh);
    Map<String, Object> academicSchedule(Map<String, Object> session, HttpServletRequest request);
    Map<String, Object> academicSchedule(Map<String, Object> session, HttpServletRequest request, boolean forceRefresh);
    Map<String, Object> markNotificationsRead(Map<String, Object> session);
    Map<String, Object> markNotificationRead(Map<String, Object> session, String notificationId);
    Map<String, Object> saveCredentials(CredentialRequest payload, Map<String, Object> session);
    Map<String, Object> verifyCredentials(Map<String, Object> session, HttpServletRequest request);
    Map<String, Object> deleteCredentials(Map<String, Object> session);
    Map<String, Object> saveSchedule(ScheduleRequest payload, Map<String, Object> session);
    Map<String, Object> runNow(Map<String, Object> session, HttpServletRequest request);
    Map<String, Object> checkQueueStatus(Map<String, Object> session);
    Map<String, Object> runQueuedAttempt(String userId);
    void finishQueuedCheck(com.seekwd.dormcheck.domain.entity.CheckJobEntity job, String status, String message, int duration);
    void runScheduled(String userId);

}
