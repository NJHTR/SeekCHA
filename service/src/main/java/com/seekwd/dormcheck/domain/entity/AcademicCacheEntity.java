package com.seekwd.dormcheck.domain.entity;

/** Persisted, non-sensitive cache envelope for campus academic data. */
public class AcademicCacheEntity {
    public String userId;
    public String cacheType;
    public String credentialVersion;
    public String payload;
    public long fetchedAt;
    public long freshUntil;
    public long staleUntil;
    public String updatedAt;
}
