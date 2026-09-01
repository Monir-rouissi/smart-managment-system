package com.smartmgmt.management.project.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.smartmgmt.management.project.ProjectStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank @Size(max = 200) String name,
        String description,
        ProjectStatus status,
        UUID customerId,
        UUID ownerId,
        LocalDate startDate,
        LocalDate dueDate) {
}
