package com.seekwd.dormcheck.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CredentialRequest(
    @NotBlank @Size(max = 128) String account,
    @NotBlank @Size(max = 256) String password) {}
