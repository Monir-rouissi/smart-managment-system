package com.smartmgmt.management.task.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.smartmgmt.management.task.TaskStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        TaskStatus status,
        @NotNull UUID projectId,
        UUID assigneeId,
        LocalDate dueDate) {
}
