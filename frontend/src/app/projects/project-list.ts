import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { PROJECT_STATUSES, Project, ProjectStatus } from './project';
import { ProjectService } from './project.service';

@Component({
  selector: 'app-project-list',
  imports: [RouterLink, FormsModule],
  templateUrl: './project-list.html',
})
export class ProjectList {
  private readonly service = inject(ProjectService);
  protected readonly auth = inject(AuthService);

  protected readonly statuses = PROJECT_STATUSES;
  protected readonly projects = signal<Project[]>([]);
  protected readonly loading = signal(true);
  protected q = '';
  protected status: ProjectStatus | '' = '';
  protected overdueOnly = false;

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service
      .list({
        q: this.q || undefined,
        status: this.status || undefined,
        overdue: this.overdueOnly || undefined,
        size: 100,
      })
      .subscribe({
        next: (page) => {
          this.projects.set(page.content);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  remove(p: Project): void {
    if (!confirm(`Delete project "${p.name}"?`)) return;
    this.service.delete(p.id).subscribe(() => this.load());
  }
}
