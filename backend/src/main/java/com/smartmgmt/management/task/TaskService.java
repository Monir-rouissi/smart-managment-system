package com.smartmgmt.management.task;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartmgmt.auth.SecurityUtils;
import com.smartmgmt.auth.UserPrincipal;
import com.smartmgmt.common.NotFoundException;
import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.project.ProjectRepository;
import com.smartmgmt.management.task.dto.TaskRequest;
import com.smartmgmt.management.task.dto.TaskResponse;
import com.smartmgmt.management.user.Role;
import com.smartmgmt.management.user.User;
import com.smartmgmt.management.user.UserRepository;

@Service
@Transactional
public class TaskService {

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final UserRepository users;

    public TaskService(TaskRepository tasks, ProjectRepository projects, UserRepository users) {
        this.tasks = tasks;
        this.projects = projects;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<TaskResponse> list(String q, TaskStatus status, UUID projectId, UUID assigneeId,
            Pageable pageable) {
        UUID effectiveAssigneeId = assigneeId;
        UserPrincipal current = SecurityUtils.currentUser();
        if (current.getRole() == Role.USER) {
            // USER sees only tasks assigned to them, regardless of what was requested.
            effectiveAssigneeId = current.getId();
        }
        return tasks.findAll(TaskSpecifications.build(q, status, projectId, effectiveAssigneeId), pageable)
                .map(TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID id) {
        Task task = findOrThrow(id);
        requireAssignedToSelfIfUser(task, "You do not have access to this task");
        return TaskResponse.from(task);
    }

    public TaskResponse create(TaskRequest request) {
        Task task = new Task();
        apply(task, request);
        return TaskResponse.from(tasks.save(task));
    }

    public TaskResponse update(UUID id, TaskRequest request) {
        Task task = findOrThrow(id);
        UserPrincipal current = SecurityUtils.currentUser();
        if (current.getRole() == Role.USER) {
            requireAssignedToSelfIfUser(task, "You can only update tasks assigned to you");
            // A USER may only change status/description/title/dueDate on their own
            // task — not hand it to someone else, unassign it, or move it.
            if (request.assigneeId() == null || !request.assigneeId().equals(current.getId())) {
                throw new AccessDeniedException("You cannot reassign or unassign this task");
            }
            if (!task.getProject().getId().equals(request.projectId())) {
                throw new AccessDeniedException("You cannot move this task to another project");
            }
        }
        apply(task, request);
        return TaskResponse.from(tasks.save(task));
    }

    public void delete(UUID id) {
        if (!tasks.existsById(id)) {
            throw new NotFoundException("Task", id);
        }
        tasks.deleteById(id);
    }

    private void requireAssignedToSelfIfUser(Task task, String message) {
        UserPrincipal current = SecurityUtils.currentUser();
        if (current.getRole() == Role.USER
                && (task.getAssignee() == null || !task.getAssignee().getId().equals(current.getId()))) {
            throw new AccessDeniedException(message);
        }
    }

    private Task findOrThrow(UUID id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("Task", id));
    }

    private void apply(Task task, TaskRequest request) {
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setStatus(request.status() != null ? request.status() : TaskStatus.TODO);
        task.setDueDate(request.dueDate());
        task.setProject(resolveProject(request.projectId()));
        task.setAssignee(resolveAssignee(request.assigneeId()));
    }

    private Project resolveProject(UUID projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project", projectId));
    }

    private User resolveAssignee(UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        return users.findById(assigneeId).orElseThrow(() -> new NotFoundException("User", assigneeId));
    }
}
