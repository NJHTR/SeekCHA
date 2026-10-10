package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.CheckJobEntity;
import com.seekwd.dormcheck.domain.entity.CampusAccessState;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CheckQueueMapper {
    int insert(CheckJobEntity job);
    CheckJobEntity findActive(@Param("userId") String userId);
    CheckJobEntity findSuccessSince(@Param("userId") String userId, @Param("since") String since);
    int attemptsSince(@Param("userId") String userId, @Param("triggerType") String triggerType,
        @Param("since") String since);
    String lastStartedAt(@Param("userId") String userId);
    CheckJobEntity nextDue(@Param("now") String now);
    List<CheckJobEntity> findRunning();
    int claim(@Param("id") String id, @Param("now") String now);
    int retry(@Param("id") String id, @Param("notBefore") String notBefore, @Param("message") String message);
    int finish(@Param("id") String id, @Param("status") String status, @Param("now") String now, @Param("message") String message);
    int cancelScheduled(@Param("userId") String userId, @Param("now") String now);
    CampusAccessState accessState();
    int acquireAccess(@Param("token") String token, @Param("now") long now, @Param("leaseUntil") long leaseUntil);
    int releaseAccess(@Param("token") String token, @Param("notBefore") long notBefore,
        @Param("cooldownUntil") long cooldownUntil, @Param("transientFailure") boolean transientFailure);
}
