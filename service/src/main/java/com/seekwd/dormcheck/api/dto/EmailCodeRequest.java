package com.seekwd.dormcheck.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailCodeRequest(@NotBlank @Email String email) {}
