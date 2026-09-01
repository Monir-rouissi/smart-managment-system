package com.smartmgmt.management.task;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartmgmt.common.NotFoundException;
import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.project.ProjectRepository;
import com.smartmgmt.management.task.dto.TaskRequest;
import com.smartmgmt.management.task.dto.TaskResponse;
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
        return tasks.findAll(TaskSpecifications.build(q, status, projectId, assigneeId), pageable)
                .map(TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID id) {
        return TaskResponse.from(findOrThrow(id));
    }

    public TaskResponse create(TaskRequest request) {
        Task task = new Task();
        apply(task, request);
        return TaskResponse.from(tasks.save(task));
    }

    public TaskResponse update(UUID id, TaskRequest request) {
        Task task = findOrThrow(id);
        apply(task, request);
        return TaskResponse.from(tasks.save(task));
    }

    public void delete(UUID id) {
        if (!tasks.existsById(id)) {
            throw new NotFoundException("Task", id);
        }
        tasks.deleteById(id);
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
