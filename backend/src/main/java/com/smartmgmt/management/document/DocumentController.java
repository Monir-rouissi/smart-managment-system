package com.smartmgmt.management.document;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.smartmgmt.management.document.dto.DocumentResponse;

@RestController
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    // Any authenticated role may attempt an upload; DocumentService checks
    // project visibility (USER: owned project only) and rejects a project-less
    // upload from a USER.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @PostMapping(value = "/api/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID projectId) {
        DocumentResponse created = service.upload(file, projectId);
        return ResponseEntity.accepted()
                .location(URI.create("/api/documents/" + created.id()))
                .body(created);
    }

    /**
     * Re-runs extraction, chunking and embedding. Restricted to ADMIN/MANAGER:
     * it is the manual retry for a FAILED document and it costs embedding calls,
     * so it is not something every USER should be able to trigger in a loop.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/api/documents/{id}/reprocess")
    public ResponseEntity<DocumentResponse> reprocess(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(service.reprocess(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping("/api/documents/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping("/api/documents/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        DocumentService.DownloadPayload payload = service.download(id);
        Document document = payload.document();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s\"".formatted(sanitizeHeaderValue(document.getName())))
                .body(payload.resource());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping("/api/projects/{projectId}/documents")
    public List<DocumentResponse> listForProject(@PathVariable UUID projectId) {
        return service.listByProject(projectId);
    }

    /** Content-Disposition header values must not contain raw quotes/newlines. */
    private static String sanitizeHeaderValue(String value) {
        return value.replace("\"", "'").replace("\r", "").replace("\n", "");
    }
}
