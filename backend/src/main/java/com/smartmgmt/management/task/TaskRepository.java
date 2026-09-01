package com.smartmgmt.management.task;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TaskRepository
        extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    @Override
    @EntityGraph(attributePaths = {"project", "assignee"})
    Optional<Task> findById(UUID id);
}
