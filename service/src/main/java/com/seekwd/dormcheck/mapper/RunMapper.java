package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.RunEntity;
import com.seekwd.dormcheck.domain.entity.RunStatsEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RunMapper {
    int insert(RunEntity run);
    List<RunEntity> findRecent(@Param("userId") String userId, @Param("limit") int limit);
    RunStatsEntity aggregateAll(@Param("userId") String userId);
    RunStatsEntity aggregateSince(@Param("userId") String userId, @Param("cutoff") String cutoff);
    long countForHistory(@Param("userId") String userId, @Param("status") String status);
    List<RunEntity> findHistory(@Param("userId") String userId, @Param("status") String status,
        @Param("limit") int limit, @Param("offset") long offset);
}
