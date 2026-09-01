import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Customer } from '../customers/customer';
import { CustomerService } from '../customers/customer.service';
import { PROJECT_STATUSES } from './project';
import { ProjectService } from './project.service';

@Component({
  selector: 'app-project-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './project-form.html',
})
export class ProjectForm {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(ProjectService);
  private readonly customerService = inject(CustomerService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly id = this.route.snapshot.paramMap.get('id');
  protected readonly isEdit = this.id !== null && this.id !== 'new';
  protected readonly statuses = PROJECT_STATUSES;
  protected readonly customers = signal<Customer[]>([]);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
    status: ['PLANNING' as (typeof PROJECT_STATUSES)[number]],
    customerId: [''],
    startDate: [''],
    dueDate: [''],
  });

  constructor() {
    this.customerService.list({ size: 100 }).subscribe((p) => this.customers.set(p.content));
    if (this.isEdit) {
      this.service.get(this.id!).subscribe((p) =>
        this.form.patchValue({
          name: p.name,
          description: p.description ?? '',
          status: p.status,
          customerId: p.customerId ?? '',
          startDate: p.startDate ?? '',
          dueDate: p.dueDate ?? '',
        }),
      );
    }
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = {
      name: v.name,
      description: v.description || null,
      status: v.status,
      customerId: v.customerId || null,
      startDate: v.startDate || null,
      dueDate: v.dueDate || null,
    };
    const req = this.isEdit ? this.service.update(this.id!, body) : this.service.create(body);
    req.subscribe({
      next: (p) => this.router.navigate(['/projects', p.id]),
      error: (e) => this.error.set(e?.error?.detail ?? 'Save failed'),
    });
  }
}
