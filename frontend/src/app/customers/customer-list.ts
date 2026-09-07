import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { Customer } from './customer';
import { CustomerService } from './customer.service';

@Component({
  selector: 'app-customer-list',
  imports: [RouterLink, FormsModule],
  templateUrl: './customer-list.html',
})
export class CustomerList {
  private readonly service = inject(CustomerService);
  protected readonly auth = inject(AuthService);

  protected readonly customers = signal<Customer[]>([]);
  protected readonly loading = signal(true);
  protected q = '';

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.list({ q: this.q, size: 100 }).subscribe({
      next: (page) => {
        this.customers.set(page.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  remove(c: Customer): void {
    if (!confirm(`Delete customer "${c.name}"?`)) return;
    this.service.delete(c.id).subscribe(() => this.load());
  }
}
