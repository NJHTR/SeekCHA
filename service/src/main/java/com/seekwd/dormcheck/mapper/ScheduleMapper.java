package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.ScheduleEntity;
import com.seekwd.dormcheck.domain.entity.SchemaColumnEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ScheduleMapper {
    ScheduleEntity findByUserId(@Param("userId") String userId);
    List<ScheduleEntity> findEnabled();
    int insertDefaults(@Param("userId") String userId, @Param("updatedAt") String updatedAt);
    int upsert(ScheduleEntity schedule);
    int disable(@Param("userId") String userId, @Param("updatedAt") String updatedAt);
    List<SchemaColumnEntity> findColumns();
    int addDaysOfWeekColumn();
}
