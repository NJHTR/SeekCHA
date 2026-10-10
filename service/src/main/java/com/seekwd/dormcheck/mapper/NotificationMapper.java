package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.NotificationEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface NotificationMapper {
    int insert(@Param("id") String id, @Param("userId") String userId,
        @Param("runId") String runId, @Param("type") String type, @Param("title") String title,
        @Param("message") String message, @Param("createdAt") String createdAt);
    int insertEvent(@Param("id") String id, @Param("userId") String userId,
        @Param("type") String type, @Param("title") String title, @Param("message") String message,
        @Param("createdAt") String createdAt);
    List<NotificationEntity> findLatest(@Param("userId") String userId, @Param("limit") int limit);
    int countUnread(@Param("userId") String userId);
    int markAllRead(@Param("userId") String userId, @Param("readAt") String readAt);
    int markOneRead(@Param("id") String id, @Param("userId") String userId, @Param("readAt") String readAt);
}
