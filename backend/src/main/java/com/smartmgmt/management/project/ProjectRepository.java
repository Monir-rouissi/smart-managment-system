package com.smartmgmt.management.project;

import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjectRepository
        extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    @Override
    @EntityGraph(attributePaths = {"customer", "owner"})
    java.util.Optional<Project> findById(UUID id);
}
