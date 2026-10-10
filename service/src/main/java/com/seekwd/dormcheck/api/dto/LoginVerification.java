package com.seekwd.dormcheck.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginVerification(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String emailCode,
    @Pattern(regexp = "^$|\\d{6}") String totpCode) {}
