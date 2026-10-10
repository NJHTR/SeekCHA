package com.seekwd.dormcheck.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 12, max = 256) String password,
    @Size(min = 1, max = 80) String name,
    @Size(min = 1, max = 80) String emailCode) {}
