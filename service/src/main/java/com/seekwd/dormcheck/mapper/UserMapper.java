package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.UserEntity;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    int existsByEmail(@Param("email") String email);
    int insert(UserEntity user);
    UserEntity findAuthenticationByEmail(@Param("email") String email);
    UserEntity findProfileById(@Param("id") String id);
    String findEmailById(@Param("id") String id);
    String findIdByEmail(@Param("email") String email);
}
