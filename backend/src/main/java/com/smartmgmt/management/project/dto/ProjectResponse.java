package com.smartmgmt.management.project.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.project.ProjectStatus;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        ProjectStatus status,
        UUID customerId,
        String customerName,
        UUID ownerId,
        String ownerName,
        LocalDate startDate,
        LocalDate dueDate,
        boolean overdue,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project p) {
        boolean overdue = p.getDueDate() != null
                && p.getDueDate().isBefore(LocalDate.now())
                && p.getStatus() != ProjectStatus.COMPLETED
                && p.getStatus() != ProjectStatus.CANCELLED;
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getStatus(),
                p.getCustomer() == null ? null : p.getCustomer().getId(),
                p.getCustomer() == null ? null : p.getCustomer().getName(),
                p.getOwner() == null ? null : p.getOwner().getId(),
                p.getOwner() == null ? null : p.getOwner().getFullName(),
                p.getStartDate(),
                p.getDueDate(),
                overdue,
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
