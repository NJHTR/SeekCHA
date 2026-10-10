package com.seekwd.dormcheck.domain.entity;

public class CheckJobEntity {
    public String id;
    public String userId;
    public String triggerType;
    public String status;
    public int attempts;
    public int maxAttempts;
    public String createdAt;
    public String notBefore;
    public String expiresAt;
    public String startedAt;
    public String finishedAt;
    public String lastError;
}
