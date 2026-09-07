package com.smartmgmt.management.task;

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
import com.smartmgmt.management.task.dto.TaskRequest;
import com.smartmgmt.management.task.dto.TaskResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("title", "status", "dueDate", "createdAt", "updatedAt");

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    // Read access is open to any authenticated role; TaskService scopes a
    // USER's list/get to tasks assigned to them.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping
    public PageResponse<TaskResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID assigneeId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortSupport.validate(pageable.getSort(), SORTABLE_FIELDS);
        return PageResponse.from(service.list(q, status, projectId, assigneeId, pageable));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
        TaskResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/tasks/" + created.id())).body(created);
    }

    // Open to USER too: TaskService only lets a USER update a task assigned to
    // them, and only status/description/title/dueDate, not project/assignee.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','USER')")
    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
