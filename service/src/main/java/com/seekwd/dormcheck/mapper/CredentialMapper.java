package com.seekwd.dormcheck.mapper;

import com.seekwd.dormcheck.domain.entity.CredentialEntity;
import org.apache.ibatis.annotations.Param;

public interface CredentialMapper {
    CredentialEntity findSummary(@Param("userId") String userId);
    CredentialEntity findEncrypted(@Param("userId") String userId);
    int upsert(CredentialEntity credential);
    int updateVerification(@Param("userId") String userId, @Param("verifiedAt") String verifiedAt,
        @Param("updatedAt") String updatedAt);
    int delete(@Param("userId") String userId);
    String findVerifiedAt(@Param("userId") String userId);
}
