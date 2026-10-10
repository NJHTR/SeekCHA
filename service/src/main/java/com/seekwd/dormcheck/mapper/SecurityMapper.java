package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.EmailCodeEntity;
import com.seekwd.dormcheck.domain.entity.UserTotpEntity;
import org.apache.ibatis.annotations.Param;

public interface SecurityMapper {
    int upsertEmailCode(@Param("email") String email, @Param("purpose") String purpose,
        @Param("codeHash") String codeHash, @Param("expiresAt") String expiresAt);
    int deleteEmailCode(@Param("email") String email, @Param("purpose") String purpose);
    EmailCodeEntity findEmailCode(@Param("email") String email, @Param("purpose") String purpose);
    int incrementEmailCodeAttempts(@Param("email") String email, @Param("purpose") String purpose);
    UserTotpEntity findTotp(@Param("userId") String userId);
    UserTotpEntity findEnabledTotp(@Param("userId") String userId);
    int upsertTotp(@Param("userId") String userId, @Param("secretCiphertext") byte[] secretCiphertext,
        @Param("updatedAt") String updatedAt);
    int enableTotp(@Param("userId") String userId, @Param("updatedAt") String updatedAt);
    int deleteTotp(@Param("userId") String userId);
    int totpEnabled(@Param("userId") String userId);
}
