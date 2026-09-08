package com.smartmgmt.management.document.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartmgmt.management.document.Document;
import com.smartmgmt.management.document.DocumentStatus;

public record DocumentResponse(
        UUID id,
        String name,
        String mimeType,
        long sizeBytes,
        DocumentStatus status,
        UUID projectId,
        String projectName,
        UUID uploadedById,
        String uploadedByName,
        Instant createdAt) {

    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getName(),
                d.getMimeType(),
                d.getSizeBytes(),
                d.getStatus(),
                d.getProject() == null ? null : d.getProject().getId(),
                d.getProject() == null ? null : d.getProject().getName(),
                d.getUploadedBy() == null ? null : d.getUploadedBy().getId(),
                d.getUploadedBy() == null ? null : d.getUploadedBy().getFullName(),
                d.getCreatedAt());
    }
}
