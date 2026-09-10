import { Component, DestroyRef, ElementRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, switchMap, take, takeWhile, tap, timer } from 'rxjs';

import { ChatPanel } from '../chat/chat-panel';
import { AuthService } from '../core/auth.service';
import { AppDocument, DocumentStatus, isDocumentPending } from '../documents/document';
import { DocumentService } from '../documents/document.service';
import { TASK_STATUSES, Task } from '../tasks/task';
import { TaskService } from '../tasks/task.service';
import { Project } from './project';
import { ProjectService } from './project.service';

/** Ingestion is async: the worker moves UPLOADED -> PROCESSING -> READY/FAILED after the upload returns. */
const POLL_INTERVAL_MS = 3000;
/** Hard cap so a document wedged in PROCESSING (dead worker) cannot poll forever in a background tab. */
const POLL_MAX_TICKS = 40;

@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, ReactiveFormsModule, ChatPanel],
  templateUrl: './project-detail.html',
})
export class ProjectDetail {
  private readonly fb = inject(FormBuilder);
  private readonly projectService = inject(ProjectService);
  private readonly taskService = inject(TaskService);
  private readonly documentService = inject(DocumentService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthService);

  private readonly id = this.route.snapshot.paramMap.get('id')!;
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  private pollSub?: Subscription;

  protected readonly taskStatuses = TASK_STATUSES;
  protected readonly project = signal<Project | null>(null);
  protected readonly tasks = signal<Task[]>([]);
  protected readonly documents = signal<AppDocument[]>([]);
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);
  /** True while the component is watching a document that ingestion has not finished with. */
  protected readonly watchingIngestion = signal(false);
  protected readonly reprocessing = signal<string | null>(null);

  protected readonly taskForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    status: ['TODO' as (typeof TASK_STATUSES)[number]],
    dueDate: [''],
  });

  constructor() {
    this.projectService.get(this.id).subscribe((p) => this.project.set(p));
    this.loadTasks();
    this.loadDocuments();
  }

  loadTasks(): void {
    this.taskService.list({ projectId: this.id, size: 100 }).subscribe((page) => this.tasks.set(page.content));
  }

  loadDocuments(): void {
    this.documentService.listForProject(this.id).subscribe((docs) => {
      this.documents.set(docs);
      this.watchIngestion();
    });
  }

  /**
   * Polls the project's document list while any document is still UPLOADED or PROCESSING.
   *
   * One request per tick for the whole list, not one per document. The list is stored before the
   * pending check runs (tap before takeWhile), so the terminal READY/FAILED state is the last thing
   * rendered and the poll then completes. Any error (expired token, project deleted) stops the poll
   * rather than retrying forever.
   */
  private watchIngestion(): void {
    if (this.pollSub && !this.pollSub.closed) return;
    if (!this.documents().some(isDocumentPending)) {
      this.watchingIngestion.set(false);
      return;
    }

    this.watchingIngestion.set(true);
    this.pollSub = timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
      .pipe(
        take(POLL_MAX_TICKS),
        switchMap(() => this.documentService.listForProject(this.id).pipe(catchError(() => EMPTY))),
        tap((docs) => this.documents.set(docs)),
        takeWhile((docs) => docs.some(isDocumentPending)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ complete: () => this.watchingIngestion.set(false) });
  }

  /** ADMIN/MANAGER can edit any task; a USER can only advance a task assigned to them. */
  canEditTask(t: Task): boolean {
    return this.auth.hasRole('ADMIN', 'MANAGER') || t.assigneeId === this.auth.user()?.id;
  }

  /** ADMIN/MANAGER can upload to any project; a USER only to a project they own. */
  canUploadDocument(): boolean {
    const p = this.project();
    return this.auth.hasRole('ADMIN', 'MANAGER') || (!!p && p.ownerId === this.auth.user()?.id);
  }

  /** Mirrors the backend: POST /api/documents/{id}/reprocess is ADMIN/MANAGER only. */
  canReprocess(d: AppDocument): boolean {
    return this.auth.hasRole('ADMIN', 'MANAGER') && !isDocumentPending(d);
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

  uploadDocument(): void {
    const input = this.fileInput()?.nativeElement;
    const file = input?.files?.[0];
    if (!file) return;

    this.uploading.set(true);
    this.uploadError.set(null);
    this.documentService.upload(file, this.id).subscribe({
      next: () => {
        this.uploading.set(false);
        if (input) input.value = '';
        this.loadDocuments();
      },
      error: (err) => {
        this.uploading.set(false);
        this.uploadError.set(err?.error?.detail ?? 'Upload failed.');
      },
    });
  }

  /**
   * A 409 means ingestion is already running for this document (another tab, or the recovery scan).
   * That is the outcome the button asked for, so refresh and start watching instead of showing an error.
   */
  reprocessDocument(d: AppDocument): void {
    this.reprocessing.set(d.id);
    this.uploadError.set(null);
    this.documentService.reprocess(d.id).subscribe({
      next: () => {
        this.reprocessing.set(null);
        this.loadDocuments();
      },
      error: (err) => {
        this.reprocessing.set(null);
        if (err?.status === 409) {
          this.loadDocuments();
          return;
        }
        this.uploadError.set(err?.error?.detail ?? 'Reprocess failed.');
      },
    });
  }

  downloadDocument(doc: AppDocument): void {
    this.documentService.download(doc);
  }

  statusLabel(d: AppDocument): string {
    const labels: Record<DocumentStatus, string> = {
      UPLOADED: 'Queued',
      PROCESSING: 'Processing…',
      READY: 'Ready',
      FAILED: 'Failed',
    };
    return labels[d.status];
  }

  statusClass(d: AppDocument): string {
    const classes: Record<DocumentStatus, string> = {
      UPLOADED: 'queued',
      PROCESSING: 'processing',
      READY: 'ready',
      FAILED: 'failed',
    };
    return classes[d.status];
  }

  /** Tooltip for the badge: the failure reason, or which model produced the vectors. */
  statusTitle(d: AppDocument): string {
    if (d.status === 'FAILED') return d.errorMessage ?? 'Ingestion failed.';
    if (d.status === 'READY') return d.embeddingModel ? `Embedded with ${d.embeddingModel}` : '';
    return '';
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  deleteProject(): void {
    const p = this.project();
    if (!p || !confirm(`Delete project "${p.name}" and its tasks?`)) return;
    this.projectService.delete(p.id).subscribe(() => this.router.navigate(['/projects']));
  }
}
