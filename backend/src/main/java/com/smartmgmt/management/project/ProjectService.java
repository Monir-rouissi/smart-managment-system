package com.smartmgmt.management.project;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.smartmgmt.common.NotFoundException;
import com.smartmgmt.management.customer.Customer;
import com.smartmgmt.management.customer.CustomerRepository;
import com.smartmgmt.management.project.dto.ProjectRequest;
import com.smartmgmt.management.project.dto.ProjectResponse;
import com.smartmgmt.management.user.User;
import com.smartmgmt.management.user.UserRepository;

import org.springframework.http.HttpStatus;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final CustomerRepository customers;
    private final UserRepository users;

    public ProjectService(ProjectRepository projects, CustomerRepository customers, UserRepository users) {
        this.projects = projects;
        this.customers = customers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> list(String q, ProjectStatus status, UUID customerId,
            UUID ownerId, Boolean overdue, Pageable pageable) {
        return projects.findAll(
                ProjectSpecifications.build(q, status, customerId, ownerId, overdue), pageable)
                .map(ProjectResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id) {
        return ProjectResponse.from(findOrThrow(id));
    }

    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project();
        apply(project, request);
        return ProjectResponse.from(projects.save(project));
    }

    public ProjectResponse update(UUID id, ProjectRequest request) {
        Project project = findOrThrow(id);
        apply(project, request);
        return ProjectResponse.from(projects.save(project));
    }

    public void delete(UUID id) {
        if (!projects.existsById(id)) {
            throw new NotFoundException("Project", id);
        }
        projects.deleteById(id);
    }

    private Project findOrThrow(UUID id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project", id));
    }

    private void apply(Project project, ProjectRequest request) {
        if (request.startDate() != null && request.dueDate() != null
                && request.dueDate().isBefore(request.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate must not be before startDate");
        }
        project.setName(request.name());
        project.setDescription(request.description());
        project.setStatus(request.status() != null ? request.status() : ProjectStatus.PLANNING);
        project.setStartDate(request.startDate());
        project.setDueDate(request.dueDate());
        project.setCustomer(resolveCustomer(request.customerId()));
        project.setOwner(resolveOwner(request.ownerId()));
    }

    private Customer resolveCustomer(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        return customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer", customerId));
    }

    private User resolveOwner(UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        return users.findById(ownerId).orElseThrow(() -> new NotFoundException("User", ownerId));
    }
}
