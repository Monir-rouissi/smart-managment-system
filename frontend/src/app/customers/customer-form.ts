import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { CustomerService } from './customer.service';

@Component({
  selector: 'app-customer-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './customer-form.html',
})
export class CustomerForm {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(CustomerService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly id = this.route.snapshot.paramMap.get('id');
  protected readonly isEdit = this.id !== null && this.id !== 'new';
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.email]],
    phone: [''],
    company: [''],
    notes: [''],
  });

  constructor() {
    if (this.isEdit) {
      this.service.get(this.id!).subscribe((c) =>
        this.form.patchValue({
          name: c.name,
          email: c.email ?? '',
          phone: c.phone ?? '',
          company: c.company ?? '',
          notes: c.notes ?? '',
        }),
      );
    }
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const body = this.form.getRawValue();
    const req = this.isEdit ? this.service.update(this.id!, body) : this.service.create(body);
    req.subscribe({
      next: () => this.router.navigate(['/customers']),
      error: (e) => this.error.set(e?.error?.detail ?? 'Save failed'),
    });
  }
}
