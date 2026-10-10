package com.seekwd.dormcheck.domain.enums;

public enum EmailCodePurpose {
    REGISTRATION("register"),
    LOGIN("login");

    private final String value;

    EmailCodePurpose(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
