package com.smartmgmt.management.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

/**
 * Writes uploaded files to a directory on disk and hands back a
 * storage-relative path to persist on the {@link Document} row. Filenames on
 * disk are always a fresh UUID — the user-supplied name is never used as a
 * path segment, so there is no path-traversal or collision risk.
 */
@Service
public class DocumentStorageService {

    private final DocumentStorageProperties properties;
    private Path root;

    public DocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        root = Path.of(properties.getDocumentsDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new DocumentStorageException("Could not create documents storage directory: " + root, e);
        }
    }

    /** Copies the upload to disk under a generated name and returns the storage-relative path. */
    public String store(MultipartFile file, String extension) {
        String relativePath = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = root.resolve(relativePath).normalize();
        if (!target.getParent().equals(root)) {
            // Defensive: extension is validated upstream, but never write outside root.
            throw new DocumentStorageException("Resolved an invalid storage path");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store uploaded file", e);
        }
        return relativePath;
    }

    public Resource load(String relativePath) {
        try {
            Path file = root.resolve(relativePath).normalize();
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new DocumentStorageException("Stored file is missing or unreadable: " + relativePath);
            }
            return resource;
        } catch (java.net.MalformedURLException e) {
            throw new DocumentStorageException("Invalid storage path: " + relativePath, e);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(root.resolve(relativePath).normalize());
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to delete stored file: " + relativePath, e);
        }
    }
}
