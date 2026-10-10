package com.seekwd.dormcheck.domain.entity;

public class UserEntity {
    public String id;
    public String email;
    public String displayName;
    public byte[] passwordSalt;
    public byte[] passwordHash;
    public String createdAt;
}
