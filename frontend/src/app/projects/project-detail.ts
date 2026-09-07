import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { TASK_STATUSES, Task } from '../tasks/task';
import { TaskService } from '../tasks/task.service';
import { Project } from './project';
import { ProjectService } from './project.service';

@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, ReactiveFormsModule],
  templateUrl: './project-detail.html',
})
export class ProjectDetail {
  private readonly fb = inject(FormBuilder);
  private readonly projectService = inject(ProjectService);
  private readonly taskService = inject(TaskService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  private readonly id = this.route.snapshot.paramMap.get('id')!;

  protected readonly taskStatuses = TASK_STATUSES;
  protected readonly project = signal<Project | null>(null);
  protected readonly tasks = signal<Task[]>([]);

  protected readonly taskForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    status: ['TODO' as (typeof TASK_STATUSES)[number]],
    dueDate: [''],
  });

  constructor() {
    this.projectService.get(this.id).subscribe((p) => this.project.set(p));
    this.loadTasks();
  }

  loadTasks(): void {
    this.taskService.list({ projectId: this.id, size: 100 }).subscribe((page) => this.tasks.set(page.content));
  }

  /** ADMIN/MANAGER can edit any task; a USER can only advance a task assigned to them. */
  canEditTask(t: Task): boolean {
    return this.auth.hasRole('ADMIN', 'MANAGER') || t.assigneeId === this.auth.user()?.id;
  }

  addTask(): void {
    if (this.taskForm.invalid) {
      this.taskForm.markAllAsTouched();
      return;
    }
    const v = this.taskForm.getRawValue();
    this.taskService
      .create({ title: v.title, status: v.status, dueDate: v.dueDate || null, projectId: this.id })
      .subscribe(() => {
        this.taskForm.reset({ title: '', status: 'TODO', dueDate: '' });
        this.loadTasks();
      });
  }

  cycleStatus(t: Task): void {
    const order = TASK_STATUSES;
    const next = order[(order.indexOf(t.status) + 1) % order.length];
    this.taskService
      .update(t.id, { title: t.title, description: t.description, status: next, projectId: t.projectId, assigneeId: t.assigneeId, dueDate: t.dueDate })
      .subscribe(() => this.loadTasks());
  }

  removeTask(t: Task): void {
    if (!confirm(`Delete task "${t.title}"?`)) return;
    this.taskService.delete(t.id).subscribe(() => this.loadTasks());
  }

  deleteProject(): void {
    const p = this.project();
    if (!p || !confirm(`Delete project "${p.name}" and its tasks?`)) return;
    this.projectService.delete(p.id).subscribe(() => this.router.navigate(['/projects']));
  }
}
