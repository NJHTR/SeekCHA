package com.seekwd.dormcheck.domain.enums;

public enum RunStatus {
    SUCCESS("success"),
    FAILURE("failure");

    private final String value;

    RunStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RunStatus fromValue(String value) {
        return "success".equals(value) ? SUCCESS : FAILURE;
    }
}
