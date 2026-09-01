import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { TASK_STATUSES, Task, TaskStatus } from './task';
import { TaskService } from './task.service';

@Component({
  selector: 'app-task-list',
  imports: [RouterLink, FormsModule],
  templateUrl: './task-list.html',
})
export class TaskList {
  private readonly service = inject(TaskService);

  protected readonly statuses = TASK_STATUSES;
  protected readonly tasks = signal<Task[]>([]);
  protected readonly loading = signal(true);
  protected q = '';
  protected status: TaskStatus | '' = '';

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.list({ q: this.q || undefined, status: this.status || undefined, size: 100 }).subscribe({
      next: (page) => {
        this.tasks.set(page.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  remove(t: Task): void {
    if (!confirm(`Delete task "${t.title}"?`)) return;
    this.service.delete(t.id).subscribe(() => this.load());
  }
}
