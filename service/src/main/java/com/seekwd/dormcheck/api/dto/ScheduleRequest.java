package com.seekwd.dormcheck.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ScheduleRequest(boolean enabled,
    @JsonProperty("run_time") @NotBlank @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$") String runTime,
    @JsonProperty("days_of_week") @NotNull @Size(min = 1, max = 7) List<Integer> daysOfWeek,
    @NotBlank @Size(max = 64) String timezone) {}
