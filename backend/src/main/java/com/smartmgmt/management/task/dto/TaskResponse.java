package com.smartmgmt.management.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.smartmgmt.management.task.Task;
import com.smartmgmt.management.task.TaskStatus;

public record TaskResponse(
        UUID id,
        String title,
        String description,
        TaskStatus status,
        UUID projectId,
        String projectName,
        UUID assigneeId,
        String assigneeName,
        LocalDate dueDate,
        boolean overdue,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskResponse from(Task t) {
        boolean overdue = t.getDueDate() != null
                && t.getDueDate().isBefore(LocalDate.now())
                && t.getStatus() != TaskStatus.DONE
                && t.getStatus() != TaskStatus.CANCELLED;
        return new TaskResponse(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getProject().getId(),
                t.getProject().getName(),
                t.getAssignee() == null ? null : t.getAssignee().getId(),
                t.getAssignee() == null ? null : t.getAssignee().getFullName(),
                t.getDueDate(),
                overdue,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
