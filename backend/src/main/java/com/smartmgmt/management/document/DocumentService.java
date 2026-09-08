package com.smartmgmt.management.document;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.smartmgmt.auth.SecurityUtils;
import com.smartmgmt.auth.UserPrincipal;
import com.smartmgmt.common.NotFoundException;
import com.smartmgmt.management.document.dto.DocumentResponse;
import com.smartmgmt.management.document.ingest.DocumentIngestRequestedEvent;
import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.project.ProjectRepository;
import com.smartmgmt.management.user.Role;
import com.smartmgmt.management.user.User;
import com.smartmgmt.management.user.UserRepository;

@Service
@Transactional
public class DocumentService {

    /** Extension -> canonical MIME type. The client's own Content-Type header is not trusted. */
    private static final Map<String, String> ALLOWED_EXTENSIONS = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain",
            "md", "text/markdown");

    private final DocumentRepository documents;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final DocumentStorageService storage;
    private final DocumentStorageProperties storageProperties;
    private final ApplicationEventPublisher events;

    public DocumentService(DocumentRepository documents, ProjectRepository projects, UserRepository users,
            DocumentStorageService storage, DocumentStorageProperties storageProperties,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.projects = projects;
        this.users = users;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.events = events;
    }

    public DocumentResponse upload(MultipartFile file, UUID projectId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        if (file.getSize() > storageProperties.getMaxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds the maximum size of %d bytes".formatted(storageProperties.getMaxFileSizeBytes()));
        }

        String originalName = cleanName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String mimeType = ALLOWED_EXTENSIONS.get(extension);
        if (mimeType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type. Allowed: " + ALLOWED_EXTENSIONS.keySet());
        }

        Project project = resolveProject(projectId);
        requireCanUpload(project);

        String storagePath = storage.store(file, extension);

        Document document = new Document();
        document.setName(originalName);
        document.setMimeType(mimeType);
        document.setSizeBytes(file.getSize());
        document.setStoragePath(storagePath);
        document.setProject(project);
        document.setUploadedBy(currentUserEntity());
        document.setStatus(DocumentStatus.UPLOADED);

        Document saved = documents.save(document);
        // Handled after this transaction commits -- a worker that starts sooner
        // would look for a row that is not visible on its connection yet.
        events.publishEvent(new DocumentIngestRequestedEvent(saved.getId()));
        return DocumentResponse.from(saved);
    }

    /**
     * Queues a document for ingestion again: after a FAILED attempt, or to
     * re-chunk a READY one with new settings. Chunks are replaced, not appended.
     */
    public DocumentResponse reprocess(UUID id) {
        Document document = findOrThrow(id);
        requireVisible(document.getProject());
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document is already being processed");
        }
        document.setStatus(DocumentStatus.UPLOADED);
        document.setErrorMessage(null);
        Document saved = documents.save(document);
        events.publishEvent(new DocumentIngestRequestedEvent(saved.getId()));
        return DocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        Document document = findOrThrow(id);
        requireVisible(document.getProject());
        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listByProject(UUID projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project", projectId));
        requireVisible(project);
        return documents.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    /** Loaded document + the disk resource it points to, for the controller to stream back. */
    public record DownloadPayload(Document document, Resource resource) {
    }

    @Transactional(readOnly = true)
    public DownloadPayload download(UUID id) {
        Document document = findOrThrow(id);
        requireVisible(document.getProject());
        return new DownloadPayload(document, storage.load(document.getStoragePath()));
    }

    private Project resolveProject(UUID projectId) {
        if (projectId == null) {
            return null;
        }
        return projects.findById(projectId).orElseThrow(() -> new NotFoundException("Project", projectId));
    }

    /** A document with no project is a global/admin upload; only ADMIN/MANAGER may create or read it. */
    private void requireCanUpload(Project project) {
        UserPrincipal current = SecurityUtils.currentUser();
        if (project == null) {
            if (current.getRole() == Role.USER) {
                throw new AccessDeniedException("Only ADMIN or MANAGER can upload a document with no project");
            }
            return;
        }
        requireVisible(project);
    }

    /** Mirrors ProjectService's visibility rule: a USER only sees projects they own. */
    private void requireVisible(Project project) {
        UserPrincipal current = SecurityUtils.currentUser();
        if (current.getRole() != Role.USER) {
            return;
        }
        if (project == null
                || project.getOwner() == null
                || !project.getOwner().getId().equals(current.getId())) {
            throw new AccessDeniedException("You do not have access to this document");
        }
    }

    private User currentUserEntity() {
        UserPrincipal current = SecurityUtils.currentUser();
        return users.findById(current.getId()).orElseThrow(() -> new NotFoundException("User", current.getId()));
    }

    private Document findOrThrow(UUID id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("Document", id));
    }

    private static String cleanName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is required");
        }
        // Strip any client-supplied path (some browsers send the full path for input[type=file]).
        String name = originalFilename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is required");
        }
        return name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
