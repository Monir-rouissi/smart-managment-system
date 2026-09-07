package com.smartmgmt.management.project;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartmgmt.common.PageResponse;
import com.smartmgmt.common.SortSupport;
import com.smartmgmt.management.project.dto.ProjectRequest;
import com.smartmgmt.management.project.dto.ProjectResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("name", "status", "startDate", "dueDate", "createdAt", "updatedAt");

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    // Read access is open to any authenticated role; ProjectService scopes a
    // USER's list/get to projects they own.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping
    public PageResponse<ProjectResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) Boolean overdue,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortSupport.validate(pageable.getSort(), SORTABLE_FIELDS);
        return PageResponse.from(service.list(q, status, customerId, ownerId, overdue, pageable));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable UUID id, @Valid @RequestBody ProjectRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
