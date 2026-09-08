package com.smartmgmt.management.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Override
    @EntityGraph(attributePaths = {"uploadedBy", "project"})
    Optional<Document> findById(UUID id);

    @EntityGraph(attributePaths = {"uploadedBy", "project"})
    List<Document> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
