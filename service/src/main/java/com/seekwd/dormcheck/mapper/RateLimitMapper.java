package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.RateLimitEntity;
import org.apache.ibatis.annotations.Param;

public interface RateLimitMapper {
    int incrementWindow(@Param("bucket") String bucket, @Param("now") long now, @Param("cutoff") long cutoff);
    RateLimitEntity find(@Param("bucket") String bucket);
    int deleteExpired(@Param("cutoff") long cutoff);
    int delete(@Param("bucket") String bucket);
    int recordFailure(@Param("bucket") String bucket, @Param("now") long now,
        @Param("resetAfterSeconds") int resetAfterSeconds);
}
