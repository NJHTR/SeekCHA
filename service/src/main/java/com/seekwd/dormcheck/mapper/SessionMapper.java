package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.SessionEntity;
import org.apache.ibatis.annotations.Param;

public interface SessionMapper {
    SessionEntity findActive(@Param("tokenHash") String tokenHash, @Param("now") String now);
    int insert(@Param("tokenHash") String tokenHash, @Param("userId") String userId,
        @Param("csrfToken") String csrfToken, @Param("expiresAt") String expiresAt);
    int insertDeviceSession(@Param("tokenHash") String tokenHash, @Param("deviceId") String deviceId);
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);
    int deleteForDevice(@Param("deviceId") String deviceId);
}
