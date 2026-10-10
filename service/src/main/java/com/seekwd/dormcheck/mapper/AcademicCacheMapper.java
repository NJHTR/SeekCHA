package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.AcademicCacheEntity;
import org.apache.ibatis.annotations.Param;

public interface AcademicCacheMapper {
    AcademicCacheEntity find(@Param("userId") String userId, @Param("cacheType") String cacheType,
        @Param("credentialVersion") String credentialVersion);
    int upsert(AcademicCacheEntity cache);
    int delete(@Param("userId") String userId, @Param("cacheType") String cacheType);
    int deleteForUser(@Param("userId") String userId);
}
