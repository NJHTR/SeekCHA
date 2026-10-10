package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.DeviceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DeviceMapper {
    String findIdByKey(@Param("userId") String userId, @Param("keyHash") String keyHash);
    String findCurrentId(@Param("userId") String userId, @Param("keyHash") String keyHash);
    int insert(DeviceEntity device);
    int update(DeviceEntity device);
    List<DeviceEntity> findByUser(@Param("userId") String userId);
    int countOwned(@Param("id") String id, @Param("userId") String userId);
    int deleteOwned(@Param("id") String id, @Param("userId") String userId);
}
