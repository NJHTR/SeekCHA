package com.seekwd.dormcheck.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginVerification(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String emailCode,
    @Pattern(regexp = "^$|\\d{6}") String totpCode) {}
