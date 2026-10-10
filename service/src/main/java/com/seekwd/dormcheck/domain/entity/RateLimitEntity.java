package com.seekwd.dormcheck.domain.entity;

public class RateLimitEntity {
    public String bucket;
    public long windowStart;
    public int attempts;
}
